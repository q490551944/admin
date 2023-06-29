package com.hpj.admin.socket.tcp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class SocketClient {

    private static final Logger logger = LoggerFactory.getLogger(SocketClient.class);

    private Bootstrap bootstrap;

    private final String host;

    private final int port;

    public SocketClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void init() {
        EventLoopGroup group = new NioEventLoopGroup();
        bootstrap = new Bootstrap();
        bootstrap.group(group).channel(NioSocketChannel.class).option(ChannelOption.TCP_NODELAY, false)
                .option(ChannelOption.SO_RCVBUF, 256 * 1024).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        pipeline.addLast(new StringEncoder());
                        pipeline.addLast(new StringDecoder());
                        pipeline.addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                logger.info("接收到消息:{}", msg);
                                super.channelRead(ctx, msg);
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                                cause.printStackTrace();
                                super.exceptionCaught(ctx, cause);
                            }

                            @Override
                            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                logger.info("连接成功：{}", ctx.channel().remoteAddress());
                                super.channelActive(ctx);
                            }
                        });
                    }
                });
    }

    public void connect() {
        try {
            ChannelFuture channelFuture = bootstrap.connect(new InetSocketAddress(host, port)).sync();
            channelFuture.isSuccess();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        SocketClient client = new SocketClient("localhost", 3000);
        new Thread(() -> {
            client.init();
            client.connect();
        }).start();
    }
}
