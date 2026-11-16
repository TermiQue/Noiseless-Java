package io.noiseless.desktop;
import javax.swing.*;
import java.awt.*;
final class ScrollColumn extends JPanel implements Scrollable {
    public Dimension getPreferredScrollableViewportSize(){ return new Dimension(400,600); }
    public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction){ return 18; }
    public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction){ return Math.max(18,visible.height-36); }
    public boolean getScrollableTracksViewportWidth(){ return true; }
    public boolean getScrollableTracksViewportHeight(){ return false; }
}
