package com.hpj.admin.util;

import com.hpj.admin.config.HttpClientDownloader;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.Spider;
import us.codecraft.webmagic.pipeline.Pipeline;
import us.codecraft.webmagic.processor.PageProcessor;
import us.codecraft.webmagic.proxy.Proxy;
import us.codecraft.webmagic.proxy.SimpleProxyProvider;
import us.codecraft.webmagic.selector.Html;
import us.codecraft.webmagic.selector.Selectable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author huangpeijun
 * @date 2020/12/14
 */
public class GetDetail implements PageProcessor {

    public static List<String> list = new ArrayList<>();
    /**
     * 设置参数
     */
    private Site site = Site.me().setCycleRetryTimes(3000).setRetryTimes(3).setSleepTime(1000).setTimeOut(30000).setCharset("UTF-8");

    @Override
    public void process(Page page) {
        Html html = page.getHtml();
        Selectable xpath = html.xpath("//div[@class='original mainPlayerDiv']/script");
        Selectable regex = xpath.regex("[flashvars_375751561]");
//        System.out.println(html);
//        Document document = html.getDocument();#app > div.block-area.block-timeline.cc_cursor > div.timeline-wrapper.block-left > div.timeline-box.clearfix > ul > li:nth-child(1)
//        String src = document.getElementsByClass("level-item").get(0).child(0).attr("src");
//        if (src.endsWith("e-big-1.png")) {
//            String name = document.getElementsByTag("h2").get(0).childNode(0).toString();
//            System.out.println("名称:" + name);
//            list.add(name);
//        }
    }

    @Override
    public Site getSite() {
        return site;
    }

    public static void main(String[] args) {
        HttpClientDownloader httpClientDownloader = new HttpClientDownloader();
        httpClientDownloader.setProxyProvider(SimpleProxyProvider.from(new Proxy("127.0.0.1",10809, "490551944@qq.com", "Aa12345.")));
        Request request = new Request();
        request.setUrl("https://cn.pornhub.com/view_video.php?viewkey=ph5fc64e52b6857");
        request.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 6.1; rv:6.0.2) Gecko/20100101 Firefox/6.0.2");
        Spider.create(new GetDetail())
                .addRequest()
                .addRequest(request)
//                .addUrl("https://cn.pornhub.com/view_video.php?viewkey=ph5fc64e52b6857")
                .setDownloader(httpClientDownloader)
                .thread(1)
                .run();
    }
    
}
