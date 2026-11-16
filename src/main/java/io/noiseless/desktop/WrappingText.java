package io.noiseless.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.font.*;
import java.text.AttributedString;

/** Width-aware native text prevents long stems/options from widening the tool pane. */
final class WrappingText extends JTextArea {
    WrappingText(String text,int size,Color color){ super(text); setEditable(false); setLineWrap(true); setWrapStyleWord(true); setFont(NativeTheme.font(size)); setForeground(color); setBackground(Color.WHITE); setMargin(new Insets(3,0,5,0)); setBorder(null); setAlignmentX(Component.LEFT_ALIGNMENT); }
    @Override public Dimension getPreferredSize(){
        int width=getParent()!=null&&getParent().getWidth()>50?getParent().getWidth():320; Insets in=getInsets(); int usable=Math.max(40,width-in.left-in.right); FontRenderContext context=new FontRenderContext(null,true,true); int lines=0;
        for(String paragraph:getText().split("\\n",-1)){
            if(paragraph.isEmpty()){ lines++; continue; } AttributedString text=new AttributedString(paragraph); text.addAttribute(TextAttribute.FONT,getFont()); LineBreakMeasurer measurer=new LineBreakMeasurer(text.getIterator(),context);
            while(measurer.getPosition()<paragraph.length()){ measurer.nextLayout(usable); lines++; }
        }
        return new Dimension(width,Math.max(1,lines)*getFontMetrics(getFont()).getHeight()+in.top+in.bottom+4);
    }
    @Override public Dimension getMaximumSize(){ return new Dimension(Integer.MAX_VALUE,getPreferredSize().height); }
    @Override public Dimension getMinimumSize(){ return new Dimension(40,getPreferredSize().height); }
}
