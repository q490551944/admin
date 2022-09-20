package com.hpj.admin.util;

import org.apache.commons.io.FileUtils;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.Spider;
import us.codecraft.webmagic.processor.PageProcessor;
import us.codecraft.webmagic.selector.Html;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author huangpeijun
 * @date 2020/12/14
 */
public class GetVideo implements PageProcessor {

    public static List<String> list = new ArrayList<>();

    /**
     * 设置参数
     */
    private Site site = Site.me().setRetryTimes(3).setSleepTime(1000).setTimeOut(3000);

    public static void main(String[] args) throws IOException {
        File file = new File("F:\\e.txt");
//        for (int i = 1; i < 870; i++) {
        Spider.create(new GetVideo())
                // 添加初始化url
                .addUrl("http://www.rrys2020.com/resourcelist")
                .thread(20)
                .run();
//        }
        String collect = String.join("\n", list);
        FileUtils.writeStringToFile(file, collect, "UTF-8");
        System.out.println(list);
    }

    @Override
    public void process(Page page) {
        Html html = page.getHtml();
        if (page.getUrl().toString().startsWith("http://www.rrys2020.com/resourcelist")) {
            List<String> all = html.xpath("//h3[@class='f14']/a").links().all();
            page.addTargetRequests(all);
            List<String> list = page.getHtml().$("div.pages > div > a").links().all();
            page.addTargetRequest(list.get(list.size() - 1));
        } else if (page.getUrl().toString().startsWith("http://www.rrys2020.com/resource")) {
            String s = html.regex("http://js.jstucdn.com/images/level-icon/\\S+\\.png").get();
            if (s.endsWith("e-big-1.png")) {
                list.add(html.xpath("//div[@class='resource-tit']/h2/text()").toString());
            }
        }
//        Document document = html.getDocument();
//        Element body = document.body();
//        Elements a = body.getElementsByClass("fl-img");
//        for (Element element : a) {
//            String href = element.getAllElements().get(1).attr("href");
//            Spider.create(new GetDetail())
//                    .addUrl(href)
//                    .thread(1)
//                    .run();
//        }
//        System.out.println(a);
    }

    @Override
    public Site getSite() {
        return site;
    }
}
