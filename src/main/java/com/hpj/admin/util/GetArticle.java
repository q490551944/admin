package com.hpj.admin.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.Spider;
import us.codecraft.webmagic.processor.PageProcessor;
import us.codecraft.webmagic.selector.Html;

public class GetArticle implements PageProcessor {

    /**
     * 设置参数
     */
    private Site site = Site.me().setRetryTimes(3).setSleepTime(1000).setTimeOut(3000);

    @Override
    public void process(Page page) {
        Html html = page.getHtml();
        Document parse = Jsoup.parse(html.get());
        Elements elements = parse.selectXpath("//title");
        Element temp = elements.first();
        while (temp != null) {
            System.out.println(temp.attr("content"));
            temp = temp.nextElementSibling();
        }
//        XPathEvaluator compile = Xsoup.compile("//title/following-sibling::*");
//        String s1 = compile.evaluate(parse).get();
//        System.out.println(s1);
//        Selectable xpath = html.xpath("//title/following-sibling::*");
//        List<String> all = xpath.all();
//        for (String s : all) {
//            System.out.println(s);
//        }
//        System.out.println(html);
    }

    @Override
    public Site getSite() {
        return site;
    }

    public static void main(String[] args) {
        Spider.create(new GetArticle())
                .addUrl("https://ask.csdn.net/questions/8411205")
                .thread(2)
                .run();
    }
}
