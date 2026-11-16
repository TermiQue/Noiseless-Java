package io.noiseless.desktop;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.ext.gfm.tables.TablesExtension;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.net.URI;
import java.util.List;

/** Swing's HTML text renderer has no JavaScript or browser engine. */
final class Transcript extends JEditorPane {
    private final Parser parser=Parser.builder().extensions(List.of(TablesExtension.create())).build();
    private final HtmlRenderer renderer=HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).extensions(List.of(TablesExtension.create())).build();
    private final StringBuilder messages=new StringBuilder(); private String streaming="";
    Transcript() {
        setContentType("text/html"); setEditable(false); setBackground(Color.WHITE); setMargin(new Insets(18,18,24,18)); putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,true); setFont(NativeTheme.font(13));
        addHyperlinkListener(e->{ if(e.getEventType()==HyperlinkEvent.EventType.ACTIVATED) try { URI uri=URI.create(e.getDescription()); if(List.of("https","http").contains(uri.getScheme())) Desktop.getDesktop().browse(uri); } catch(Exception ignored) {} });
        refresh();
    }
    void message(String role,String text,String quote) {
        if(role.equals("assistant")) streaming=""; messages.append("<div class=\"").append(role.equals("user")?"user":"assistant").append("\">");
        if(quote!=null&&!quote.isBlank()) messages.append("<blockquote>").append(markdown(quote)).append("</blockquote>");
        messages.append(markdown(text)).append("</div>"); refresh();
    }
    void streaming(String text){ streaming=text; refresh(); }
    void stopStreaming(){ if(!streaming.isEmpty()){ String text=streaming; streaming=""; message("assistant",text+"\n\n*已停止*",null); } }
    void sources(com.fasterxml.jackson.databind.JsonNode sources){ if(!sources.isArray()||sources.isEmpty()) return; messages.append("<p>来源</p><ul>"); for(var source:sources){ try{ URI uri=URI.create(source.path("url").asText()); if(!java.util.Set.of("http","https").contains(uri.getScheme())) continue; messages.append("<li><a href=\"").append(escape(uri.toString())).append("\">").append(escape(source.path("title").asText())).append("</a></li>"); }catch(IllegalArgumentException ignored){} } messages.append("</ul>"); refresh(); }
    private static String escape(String value){ return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }
    private String markdown(String text) { return renderer.render(parser.parse(text)).replaceAll("(?is)<img\\b[^>]*>","[图片]"); }
    private void refresh() {
        setText("<html><head><style>body{font-family:'"+NativeTheme.FONT+"';font-size:13px;color:#242726;}p{margin-top:10px;margin-bottom:12px;} .user{background-color:#f6f7f6;padding:12px;margin-bottom:20px;} .assistant{padding:4px;margin-bottom:22px;} blockquote{color:#858a86;margin-left:8px;border-left:2px solid #dce2dc;padding-left:10px;}pre,code{background-color:#f6f7f6;}table{border-collapse:collapse;}td,th{border:1px solid #eceeec;padding:5px;}a{color:#428675;}</style></head><body>"+messages+(streaming.isEmpty()?"":"<div class=assistant>"+markdown(streaming)+"</div>")+"</body></html>");
        setCaretPosition(getDocument().getLength());
    }
}
