package io.noiseless.desktop;
import javax.swing.*;
import java.awt.*;
/** The original article's 760px text measure, centered when the support pane is closed. */
final class ReadingPage extends JPanel implements Scrollable {
    private final JTextPane text;
    ReadingPage(JTextPane text){ this.text=text; setLayout(null); add(text); setBackground(Color.WHITE); }
    public Dimension getPreferredSize(){ int width=getParent()!=null&&getParent().getWidth()>0?getParent().getWidth():800; int measure=Math.min(width,836); text.setSize(Math.max(160,measure),Short.MAX_VALUE); return new Dimension(width,text.getPreferredSize().height); }
    public void doLayout(){ int measure=Math.min(getWidth(),836); text.setBounds(Math.max(0,(getWidth()-measure)/2),0,measure,text.getPreferredSize().height); }
    public Dimension getPreferredScrollableViewportSize(){ return new Dimension(836,600); }
    public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction){ return 24; }
    public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction){ return visible.height-40; }
    public boolean getScrollableTracksViewportWidth(){ return true; }
    public boolean getScrollableTracksViewportHeight(){ return false; }
}
