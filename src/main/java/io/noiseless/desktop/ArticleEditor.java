package io.noiseless.desktop;

import io.noiseless.core.Article;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import static io.noiseless.core.Json.*;

/** Form editing preserves unknown JSON fields without exposing storage syntax in normal flows. */
final class ArticleEditor extends JDialog {
    private final ObjectNode original;
    private final List<ParagraphFields> paragraphs=new ArrayList<>();
    private final List<QuestionFields> questions=new ArrayList<>();
    private final JPanel body=new JPanel();
    private final JLabel error=new JLabel(" ");
    private final JTextField title,subtitle;
    private record ParagraphFields(ObjectNode original,JTextArea en,JTextArea zh,JTextField label,JPanel panel) {}
    private record QuestionFields(ObjectNode original,JTextArea answer,JTextArea explanation) {}
    ArticleEditor(Frame owner,String caption,JsonNode article,Consumer<JsonNode> save) {
        super(owner,caption,false); original=(ObjectNode)article.deepCopy();
        JPanel shell=new JPanel(new BorderLayout(0,16)); shell.setBorder(BorderFactory.createEmptyBorder(22,26,20,26));
        JLabel heading=new JLabel(caption); heading.setFont(NativeTheme.font(20).deriveFont(Font.BOLD)); shell.add(heading,BorderLayout.NORTH);
        body.setLayout(new BoxLayout(body,BoxLayout.Y_AXIS)); body.setBorder(BorderFactory.createEmptyBorder(0,0,14,10));
        title=new JTextField(article.path("title").asText()); subtitle=new JTextField(article.path("subtitle").asText());
        body.add(field("文章标题",title)); body.add(field("副标题",subtitle));
        for(JsonNode p:article.path("paragraphs")) addParagraph((ObjectNode)p.deepCopy());
        JButton add=NativeTheme.button("＋ 添加段落",()->{ addParagraph(obj("en","","zh","")); refreshParagraphs(); });
        JPanel contents=new JPanel(new BorderLayout()); contents.add(body); contents.add(add,BorderLayout.SOUTH);
        JPanel lower=new JPanel(); lower.setLayout(new BoxLayout(lower,BoxLayout.Y_AXIS));
        for(JsonNode q:article.path("questions")){
            JTextArea answer=area(q.path("answer").asText(),3),explanation=area(q.path("explanation").asText(),3);
            lower.add(field("第 "+q.path("number").asInt()+" 题 · 参考答案",new JScrollPane(answer))); lower.add(field("解析",new JScrollPane(explanation))); questions.add(new QuestionFields((ObjectNode)q.deepCopy(),answer,explanation));
        }
        JPanel all=new JPanel(new BorderLayout(0,20)); all.add(contents,BorderLayout.NORTH); all.add(lower);
        JScrollPane scroll=new JScrollPane(all); scroll.getVerticalScrollBar().setUnitIncrement(18); shell.add(scroll);
        JPanel footer=new JPanel(new BorderLayout()); error.setForeground(new Color(0xab372f)); footer.add(error,BorderLayout.NORTH); JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel=NativeTheme.button("取消",this::dispose); JButton submit=NativeTheme.button("保存",()->{ try { save.accept(value()); error.setText(" "); } catch(Exception e){ error.setText(e.getMessage()); } }); submit.setForeground(NativeTheme.GREEN); buttons.add(cancel); buttons.add(submit); footer.add(buttons,BorderLayout.SOUTH); shell.add(footer,BorderLayout.SOUTH);
        setContentPane(shell); setSize(820,740); setLocationRelativeTo(owner); getRootPane().registerKeyboardAction(e->dispose(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
    }
    void error(String message){ error.setText(message); }
    JsonNode value(){
        ObjectNode result=original.deepCopy(); result.put("title",title.getText()); result.put("subtitle",subtitle.getText()); ArrayNode ps=arr();
        for(var p:paragraphs){ ObjectNode value=p.original().deepCopy(); value.put("en",p.en().getText()); value.put("zh",p.zh().getText()); if(!p.label().getText().isBlank()) value.put("label",p.label().getText()); else value.remove("label"); ps.add(value); } result.set("paragraphs",ps);
        if(original.has("questions")){ ArrayNode qs=arr(); for(var q:questions){ ObjectNode value=q.original().deepCopy(); value.put("answer",q.answer().getText()); value.put("explanation",q.explanation().getText()); qs.add(value); } result.set("questions",qs); }
        return Article.validate(result);
    }
    private void addParagraph(ObjectNode data){
        JPanel p=new JPanel(); p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS)); p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,0,0,0,NativeTheme.LINE),BorderFactory.createEmptyBorder(16,0,16,0)));
        JTextArea en=area(data.path("en").asText(),4),zh=area(data.path("zh").asText(),3); JTextField label=new JTextField(data.path("label").asText());
        ParagraphFields fields=new ParagraphFields(data,en,zh,label,p); paragraphs.add(fields);
        JPanel toolbar=new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0)); toolbar.add(NativeTheme.button("上移",()->move(fields,-1))); toolbar.add(NativeTheme.button("下移",()->move(fields,1))); toolbar.add(NativeTheme.button("移除段落",()->{ if(paragraphs.size()>1){ paragraphs.remove(fields); refreshParagraphs(); } }));
        p.add(toolbar); if(original.path("type").asText().equals("matching")) p.add(field("段落标签",label)); p.add(field("原文",new JScrollPane(en))); p.add(field("译文",new JScrollPane(zh))); body.add(p);
    }
    private void move(ParagraphFields p,int delta){ int index=paragraphs.indexOf(p),target=index+delta; if(target>=0&&target<paragraphs.size()){ Collections.swap(paragraphs,index,target); refreshParagraphs(); } }
    private void refreshParagraphs(){ for(var p:paragraphs) body.remove(p.panel()); while(body.getComponentCount()>2) body.remove(2); for(var p:paragraphs) body.add(p.panel()); body.revalidate(); body.repaint(); }
    private static JPanel field(String label,JComponent input){ JPanel p=new JPanel(new BorderLayout(0,7)); p.setBorder(BorderFactory.createEmptyBorder(0,0,15,0)); JLabel name=new JLabel(label); name.setForeground(NativeTheme.MUTED); p.add(name,BorderLayout.NORTH); p.add(input); return p; }
    private static JTextArea area(String value,int rows){ JTextArea a=new JTextArea(value,rows,40); a.setLineWrap(true); a.setWrapStyleWord(true); a.setMargin(new Insets(10,12,10,12)); return a; }
}
