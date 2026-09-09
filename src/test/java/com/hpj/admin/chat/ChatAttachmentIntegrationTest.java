package com.hpj.admin.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.entity.chat.*;
import com.hpj.admin.mapper.chat.ChatAuditEventMapper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ChatTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=classpath:/chat-test.yml",
                "spring.datasource.url=jdbc:h2:mem:chat_attachments;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class ChatAttachmentIntegrationTest {
    @Autowired ChatAttachmentService attachments;
    @Autowired ChatMessagingService messages;
    @Autowired ChatRoomService rooms;
    @Autowired ChatDirectService direct;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ChatMetrics metrics;
    @MockBean ChatObjectStorage storage;
    @SpyBean ChatAuditEventMapper audits;
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    long room;

    org.springframework.test.web.servlet.request.RequestPostProcessor as(long user) {
        var principal = new com.hpj.admin.chat.security.ChatPrincipal(user,
                jdbc.queryForObject("SELECT username FROM user WHERE id=?", String.class, user), "", true);
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @BeforeEach void seed() {
        ChatIntegrationTest.clean(jdbc);
        objects.clear();
        for (int id = 1; id <= 3; id++) jdbc.update("INSERT INTO user(id,username,password,status) VALUES(?,?,?,TRUE)",
                id, List.of("alice", "bob", "carol").get(id - 1), ChatIntegrationTest.HASH);
        room = rooms.create(1, "Images").getId();
        doAnswer(call -> { objects.put(call.getArgument(0) + "/" + call.getArgument(1), call.getArgument(2)); return null; })
                .when(storage).put(anyString(), anyString(), any(), anyString());
        when(storage.read(anyString(), anyString(), anyInt())).thenAnswer(call -> {
            var bytes = objects.get(call.getArgument(0) + "/" + call.getArgument(1));
            if (bytes == null) throw new IllegalStateException("missing test object");
            return bytes;
        });
        doAnswer(call -> { objects.remove(call.getArgument(0) + "/" + call.getArgument(1)); return null; })
                .when(storage).delete(anyString(), anyString());
    }

    static byte[] image(String format) throws IOException {
        var output = new ByteArrayOutputStream();
        var image = new BufferedImage(640, 320, BufferedImage.TYPE_INT_RGB);
        image.setRGB(2, 3, 0xff11aabb);
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }

    AttachmentView prepare(long conversation, byte[] bytes, String type) {
        return attachments.prepare(1, conversation, new ChatAttachmentService.Prepare("../sample.png", type, (long)bytes.length));
    }

    AttachmentView ready(long conversation) throws IOException {
        var bytes = image("png");
        var pending = prepare(conversation, bytes, "image/png");
        return attachments.upload(1, pending.id(), new ByteArrayInputStream(bytes), bytes.length);
    }

    MessageView send(long user, long conversation, String request, long attachment) {
        return messages.send(user, new ChatMessageRequest(conversation, request, MessageType.IMAGE, null, attachment), null, null);
    }

    @Test void uploadsRealImagesBuildsThumbnailAndReturnsControlledHistoryMetadata() throws Exception {
        double persistedBefore = metrics.persisted.count();
        double uploadedBefore = metrics.uploaded.count();
        for (String format : List.of("png", "jpeg", "gif")) {
            byte[] bytes = image(format);
            var pending = prepare(room, bytes, "image/" + format);
            assertThat(pending.status()).isEqualTo("UPLOADING");
            var uploaded = attachments.upload(1, pending.id(), new ByteArrayInputStream(bytes), bytes.length);
            assertThat(uploaded.status()).isEqualTo("READY");
            assertThat(uploaded.width()).isEqualTo(640);
            assertThat(uploaded.height()).isEqualTo(320);
            assertThat(uploaded.origFilename()).doesNotContain("/");
            var message = send(1, room, format, pending.id());
            assertThat(message.getAttachment().contentType()).isEqualTo("image/" + format);
            assertThat(send(1, room, format, pending.id()).getId()).isEqualTo(message.getId());
            assertThat(attachments.content(2, pending.id(), false).bytes()).isEqualTo(bytes);
            var thumb = ImageIO.read(new ByteArrayInputStream(attachments.content(2, pending.id(), true).bytes()));
            assertThat(thumb.getWidth()).isEqualTo(480);
            assertThat(thumb.getHeight()).isEqualTo(240);
            mvc.perform(get("/api/chat/v1/attachments/" + pending.id() + "/content").with(as(2)))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
        var history = messages.history(2, room, null, null, 30);
        assertThat(history.items()).hasSize(3).allSatisfy(item -> assertThat(item.getAttachment().thumbnailUrl()).endsWith("/thumbnail"));
        assertThat(json.writeValueAsString(history)).doesNotContain("storageBucket", "storageObjectKey", "sha256");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_attachment WHERE status='ATTACHED'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_audit_event WHERE event_type='ATTACHMENT_UPLOADED'", Integer.class)).isEqualTo(3);
        assertThat(metrics.persisted.count() - persistedBefore).isEqualTo(3);
        assertThat(metrics.uploaded.count() - uploadedBefore).isEqualTo(3);
    }

    @Test void httpUploadRequiresAuthenticationCsrfAndAcceptsRawBytes() throws Exception {
        byte[] bytes = image("png");
        String request = json.writeValueAsString(new ChatAttachmentService.Prepare("sample.png", "image/png", (long)bytes.length));
        String path = "/api/chat/v1/conversations/" + room + "/attachments";
        mvc.perform(post(path).with(csrf()).contentType("application/json").content(request)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(as(1)).contentType("application/json").content(request)).andExpect(status().isForbidden());
        String response = mvc.perform(post(path).with(as(1)).with(csrf())
                .contentType("application/json").content(request)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String upload = json.readTree(response).path("uploadUrl").asText();
        mvc.perform(put(upload).with(as(1)).contentType("image/png").content(bytes)).andExpect(status().isForbidden());
        mvc.perform(put(upload).with(as(2)).with(csrf()).contentType("image/png").content(bytes)).andExpect(status().isForbidden());
        mvc.perform(put(upload).with(as(1)).with(csrf()).contentType("image/png").content(bytes))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
    }

    @Test void mismatchedDeclaredTypeUsesDecodedFormatForUploadSendHistoryAndDownload() throws Exception {
        for (String format : List.of("png", "jpeg", "gif", "webp")) {
            byte[] bytes;
            if (format.equals("webp")) {
                try (var input = getClass().getResourceAsStream("/chat/sample.webp")) {
                    assertThat(input).isNotNull();
                    bytes = input.readAllBytes();
                }
            } else bytes = image(format);
            String actualType = "image/" + format;
            String declaredType = format.equals("png") ? "image/jpeg" : "image/png";
            var pending = prepare(room, bytes, declaredType);
            mvc.perform(put(pending.uploadUrl()).with(as(1)).with(csrf()).contentType(declaredType).content(bytes))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
                    .andExpect(jsonPath("$.contentType").value(actualType));
            assertThat(jdbc.queryForObject("SELECT content_type FROM chat_attachment WHERE id=?", String.class, pending.id()))
                    .isEqualTo(actualType);
            verify(storage).put(anyString(), argThat(key -> !key.endsWith(".thumb.png")), eq(bytes), eq(actualType));
            var message = send(1, room, "mismatched-" + format, pending.id());
            assertThat(message.getAttachment().status()).isEqualTo("ATTACHED");
            assertThat(message.getAttachment().contentType()).isEqualTo(actualType);
            assertThat(send(1, room, "mismatched-" + format, pending.id()).getId()).isEqualTo(message.getId());
            assertThat(messages.history(2, room, null, null, 30).items())
                    .anySatisfy(item -> {
                        assertThat(item.getId()).isEqualTo(message.getId());
                        assertThat(item.getAttachment().contentType()).isEqualTo(actualType);
                    });
            mvc.perform(get(pending.contentUrl()).with(as(2))).andExpect(status().isOk())
                    .andExpect(content().contentType(actualType)).andExpect(content().bytes(bytes));
            mvc.perform(get(pending.thumbnailUrl()).with(as(2))).andExpect(status().isOk())
                    .andExpect(content().contentType("image/png"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isEqualTo(4);
    }

    @Test void rejectsDisguisedSvgAndOversizeWithoutCreatingMessages() throws Exception {
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes();
        var pending = prepare(room, svg, "image/png");
        assertThatThrownBy(() -> attachments.upload(1, pending.id(), new ByteArrayInputStream(svg), svg.length))
                .isInstanceOf(ChatException.class).extracting(e -> ((ChatException)e).getStatus()).isEqualTo(415);
        assertThat(jdbc.queryForObject("SELECT status FROM chat_attachment WHERE id=?", String.class, pending.id())).isEqualTo("FAILED");
        byte[] png = image("png");
        assertThatThrownBy(() -> attachments.prepare(1, room, new ChatAttachmentService.Prepare("big.png", "image/png", 10_485_761L)))
                .isInstanceOf(ChatException.class);
        var truncated = prepare(room, png, "image/png");
        assertThatThrownBy(() -> attachments.upload(1, truncated.id(), new ByteArrayInputStream(new byte[10_485_761]), -1))
                .isInstanceOf(ChatException.class).extracting(e -> ((ChatException)e).getStatus()).isEqualTo(413);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isZero();
        assertThat(objects).isEmpty();
    }

    @Test void attachmentCannotBeStolenReusedOrSentBeforeReady() throws Exception {
        byte[] bytes = image("png");
        var pending = prepare(room, bytes, "image/png");
        assertThatThrownBy(() -> send(1, room, "before-ready", pending.id())).isInstanceOf(ChatException.class);
        attachments.upload(1, pending.id(), new ByteArrayInputStream(bytes), bytes.length);
        assertThatThrownBy(() -> send(2, room, "stolen", pending.id())).isInstanceOf(ChatException.class);
        long other = rooms.create(1, "Other images").getId();
        assertThatThrownBy(() -> send(1, other, "cross-room", pending.id())).isInstanceOf(ChatException.class);
        send(1, room, "first", pending.id());
        assertThatThrownBy(() -> send(1, room, "second", pending.id())).isInstanceOf(ChatException.class);
        assertThatThrownBy(() -> messages.send(1, new TextMessageRequest(room, "first", "changed"), null, null)).isInstanceOf(ChatException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isEqualTo(1);
    }

    @Test void privateImagesAreHiddenFromThirdUserAndDissolvedRooms() throws Exception {
        long conversation = direct.open(1, 2).getId();
        var uploaded = ready(conversation);
        send(1, conversation, "private-image", uploaded.id());
        for (String kind : List.of("content", "thumbnail")) {
            mvc.perform(get("/api/chat/v1/attachments/" + uploaded.id() + "/" + kind).with(as(3)))
                    .andExpect(status().isForbidden());
        }
        verify(storage, never()).read(anyString(), anyString(), anyInt());
        assertThat(attachments.content(2, uploaded.id(), false).bytes()).isNotEmpty();
        var publicImage = ready(room);
        send(1, room, "public-image", publicImage.id());
        rooms.dissolve(1, room);
        assertThatThrownBy(() -> attachments.content(2, publicImage.id(), false)).isInstanceOf(ChatException.class);
    }

    @Test void messageRollbackLeavesAttachmentReadyAndDoesNotPublish() throws Exception {
        double failedBefore = metrics.persistenceFailed.count();
        var uploaded = ready(room);
        doThrow(new IllegalStateException("audit unavailable")).when(audits).insert(argThat((ChatAuditEvent event) -> event.getEventType() == AuditEventType.MESSAGE_CREATED));
        assertThatThrownBy(() -> send(1, room, "rollback-image", uploaded.id())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM chat_attachment WHERE id=?", String.class, uploaded.id())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT message_id FROM chat_attachment WHERE id=?", Long.class, uploaded.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isZero();
        assertThat(metrics.persistenceFailed.count() - failedBefore).isEqualTo(1);
    }

    @Test void failedStorageUploadRemainsCleanableAndNeverBecomesReady() throws Exception {
        byte[] bytes = image("png");
        var pending = prepare(room, bytes, "image/png");
        doThrow(new IllegalStateException("thumbnail store failed")).when(storage).put(anyString(), endsWith(".thumb.png"), any(), anyString());
        assertThatThrownBy(() -> attachments.upload(1, pending.id(), new ByteArrayInputStream(bytes), bytes.length)).isInstanceOf(IllegalStateException.class);
        assertThat(objects).hasSize(1);
        assertThat(attachments.cleanupExpired()).isEqualTo(1);
        assertThat(objects).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_attachment", Integer.class)).isZero();
    }

    @Test void cleanupDeletesExpiredOrphansButRetainsAttachedImagesAndRetriesDeletionFailure() throws Exception {
        var keep = ready(room);
        send(1, room, "keep", keep.id());
        var orphan = ready(room);
        jdbc.update("UPDATE chat_attachment SET expires_at=TIMESTAMP '2000-01-01 00:00:00'");
        doThrow(new IllegalStateException("delete unavailable")).when(storage).delete(anyString(), anyString());
        assertThat(attachments.cleanupExpired()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_attachment", Integer.class)).isEqualTo(2);
        doAnswer(call -> { objects.remove(call.getArgument(0) + "/" + call.getArgument(1)); return null; }).when(storage).delete(anyString(), anyString());
        assertThat(attachments.cleanupExpired()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM chat_attachment", Long.class)).containsExactly(keep.id());
        assertThat(attachments.content(2, keep.id(), false).bytes()).isNotEmpty();
    }

    @Test void concurrentRequestsAssociateAttachmentOnce() throws Exception {
        var uploaded = ready(room);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var calls = new ArrayList<Callable<Long>>();
            for (int i = 0; i < 4; i++) calls.add(() -> send(1, room, "same-image", uploaded.id()).getId());
            Set<Long> ids = new HashSet<>();
            for (var result : pool.invokeAll(calls)) ids.add(result.get(10, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void slowStorageDoesNotBlockTextOrDissolutionAndRevalidatesBeforeReady() throws Exception {
        byte[] bytes = image("png");
        var pending = prepare(room, bytes, "image/png");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("storage gate timed out");
            objects.put(call.getArgument(0) + "/" + call.getArgument(1), call.getArgument(2));
            return null;
        }).when(storage).put(anyString(), anyString(), any(), anyString());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var upload = pool.submit(() -> attachments.upload(1, pending.id(), new ByteArrayInputStream(bytes), bytes.length));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            pool.submit(() -> {
                assertThatThrownBy(() -> send(1, room, "not-ready", pending.id())).isInstanceOf(ChatException.class)
                        .extracting(error -> ((ChatException)error).getCode()).isEqualTo("ATTACHMENT_NOT_READY");
                messages.send(2, new TextMessageRequest(room, "during-upload", "text is available"), "bob", null);
                rooms.dissolve(1, room);
            }).get(3, TimeUnit.SECONDS);
            release.countDown();
            assertThatThrownBy(() -> upload.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ChatException.class);
            assertThat(jdbc.queryForObject("SELECT status FROM chat_attachment WHERE id=?", String.class, pending.id())).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE message_type='IMAGE'", Integer.class)).isZero();
            assertThat(attachments.cleanupExpired()).isEqualTo(1);
            assertThat(objects).isEmpty();
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
