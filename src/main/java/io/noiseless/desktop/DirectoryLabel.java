package io.noiseless.desktop;
import javax.swing.*;
import java.awt.*;
/** Reveal overflow titles at the original UI's 30px/s instead of widening the directory. */
final class DirectoryLabel extends JLabel {
    private final JTree tree; private final int row;
    DirectoryLabel(String text,JTree tree,int row){ super(text); this.tree=tree; this.row=row; }
    protected void paintComponent(Graphics graphics){
        int overflow=getFontMetrics(getFont()).stringWidth(getText())-getWidth(); Object hovered=tree.getClientProperty("noiseless.hoverRow"),start=tree.getClientProperty("noiseless.hoverStart");
        if(overflow<=0||!(hovered instanceof Integer index)||index!=row||!(start instanceof Long began)){ super.paintComponent(graphics); return; }
        double travel=overflow/30.0,pause=.8,period=(travel+pause)*2,seconds=(System.nanoTime()-began)/1e9,position=seconds%period;
        double offset=position<pause?0:position<pause+travel?(position-pause)*30:position<2*pause+travel?overflow:Math.max(0,overflow-(position-2*pause-travel)*30);
        Graphics2D g=(Graphics2D)graphics.create(); g.clipRect(0,0,getWidth(),getHeight()); g.setColor(getForeground()); g.setFont(getFont()); FontMetrics metrics=g.getFontMetrics(); g.drawString(getText(),-(int)offset,(getHeight()+metrics.getAscent()-metrics.getDescent())/2); g.dispose();
    }
}
