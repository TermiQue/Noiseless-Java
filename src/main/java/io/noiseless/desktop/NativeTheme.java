package io.noiseless.desktop;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.basic.*;
import java.awt.*;
import java.awt.geom.*;

/** The existing renderer's design tokens, painted with native Java2D. */
final class NativeTheme {
    static final Color WHITE=Color.WHITE, CHROME=new Color(0xf6f7f6), TEXT=new Color(0x242726), MUTED=new Color(0x858a86), GREEN=new Color(0x428675), LINE=new Color(0xeceeec);
    static final String FONT=System.getProperty("os.name").contains("Mac")?"Helvetica Neue":Font.SANS_SERIF;
    static Font font(int size) { return new Font(FONT,Font.PLAIN,size); }
    static void install() {
        UIManager.put("Panel.background",WHITE); UIManager.put("Label.foreground",TEXT); UIManager.put("Label.font",font(13));
        UIManager.put("TextArea.font",font(14)); UIManager.put("TextArea.background",WHITE); UIManager.put("TextArea.foreground",TEXT);
        UIManager.put("TextField.font",font(13)); UIManager.put("Button.font",font(12)); UIManager.put("CheckBox.font",font(12));
        UIManager.put("ScrollPane.border",BorderFactory.createEmptyBorder()); UIManager.put("Tree.font",font(13));
        UIManager.put("Tree.background",WHITE); UIManager.put("Tree.textBackground",WHITE); UIManager.put("Tree.selectionBackground",new Color(0xf2f3f2));
        UIManager.put("Tree.selectionForeground",TEXT); UIManager.put("Tree.selectionBorderColor",new Color(0xf2f3f2));
        UIManager.put("SplitPane.background",LINE); UIManager.put("SplitPaneDivider.border",BorderFactory.createEmptyBorder());
    }
    static JButton button(String label,Runnable action) {
        JButton b=new JButton(label); b.setUI(new BasicButtonUI()); b.setFont(font(12)); b.setForeground(TEXT);
        b.setOpaque(false); b.setContentAreaFilled(false); b.setBorder(new EmptyBorder(7,10,7,10)); b.setFocusPainted(true);
        b.addActionListener(e->action.run()); b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); return b;
    }
    static JButton icon(String name,String tooltip,Runnable action) {
        JButton b=button("",action); b.setIcon(new LineIcon(name)); b.setForeground(MUTED); b.setToolTipText(tooltip); b.setPreferredSize(new Dimension(30,30)); b.setMaximumSize(new Dimension(30,30)); b.setAlignmentX(Component.CENTER_ALIGNMENT); b.setBorder(new EmptyBorder(5,5,5,5));
        b.addMouseListener(new java.awt.event.MouseAdapter(){ public void mouseEntered(java.awt.event.MouseEvent e){ b.setContentAreaFilled(true); b.setBackground(new Color(0xe8e8e8)); } public void mouseExited(java.awt.event.MouseEvent e){ b.setContentAreaFilled(false); } }); return b;
    }
    static void split(JSplitPane split) { split.setBorder(null); split.setDividerSize(1); split.setContinuousLayout(true); split.setUI(new BasicSplitPaneUI(){ @Override public BasicSplitPaneDivider createDefaultDivider(){ return new BasicSplitPaneDivider(this){ @Override public void paint(Graphics g){ g.setColor(LINE); g.fillRect(0,0,getWidth(),getHeight()); } }; } }); split.setBorder(BorderFactory.createEmptyBorder()); }
    static void tabs(JTabbedPane tabs) {
        tabs.setBackground(CHROME); tabs.setForeground(TEXT); tabs.setFont(font(12)); tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.setUI(new BasicTabbedPaneUI(){
            @Override protected void installDefaults(){ super.installDefaults(); tabInsets=new Insets(8,13,8,13); tabAreaInsets=new Insets(4,6,4,6); contentBorderInsets=new Insets(0,0,0,0); selectedTabPadInsets=new Insets(0,0,0,0); }
            @Override protected void paintTabBorder(Graphics g,int p,int i,int x,int y,int w,int h,boolean selected){ if(selected){ g.setColor(new Color(0xe2e7e1)); g.drawRoundRect(x,y,w-1,h-1,9,9); } }
            @Override protected void paintTabBackground(Graphics g,int p,int i,int x,int y,int w,int h,boolean selected){ g.setColor(selected?WHITE:CHROME); g.fillRoundRect(x,y,w,h,9,9); }
            @Override protected void paintContentBorder(Graphics g,int p,int selected){}
            @Override protected void paintFocusIndicator(Graphics g,int p,Rectangle[] rects,int i,Rectangle icon,Rectangle text,boolean selected){}
        });
    }
    static JPanel chrome(LayoutManager layout) { JPanel p=new JPanel(layout); p.setBackground(CHROME); return p; }
    static final class LineIcon implements Icon {
        private final String name; LineIcon(String name){ this.name=name; }
        public int getIconWidth(){ return 18; } public int getIconHeight(){ return 18; }
        public void paintIcon(Component c,Graphics graphics,int x,int y) {
            Graphics2D g=(Graphics2D)graphics.create(); g.translate(x,y); g.scale(.75,.75); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); g.setColor(c.getForeground()); g.setStroke(new BasicStroke(1.45f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            switch(name) {
                case "back" -> path(g,15,5,8,12,15,19);
                case "forward" -> path(g,9,5,16,12,9,19);
                case "home" -> { path(g,3,10,12,3,21,10,21,21,3,21,3,10); g.drawRoundRect(9,12,6,9,1,1); }
                case "blank" -> { path(g,5,2,15,2,20,7,20,22,5,22,5,2); path(g,15,2,15,7,20,7); }
                case "book", "vocabulary" -> { path(g,12,5,8,3,2,3,2,19,8,19,12,21,16,19,22,19,22,3,16,3,12,5,12,21); if(name.equals("vocabulary")) path(g,16,3,16,10,19,8,21,10); }
                case "folder", "folderAdd" -> { path(g,2,7,2,4,9,4,12,7,22,7,22,20,2,20,2,7); if(name.equals("folderAdd")){ path(g,12,11,12,17); path(g,9,14,15,14); } }
                case "panel", "panelRight" -> { g.drawRoundRect(3,3,18,18,2,2); path(g,name.equals("panel")?9:15,3,name.equals("panel")?9:15,21); }
                case "stop" -> g.drawRect(7,7,10,10);
                case "retry" -> { g.drawArc(4,4,16,16,30,300); path(g,3,3,3,10,10,10); }
                case "close" -> { path(g,7,7,17,17); path(g,17,7,7,17); }
                case "plus" -> { path(g,12,5,12,19); path(g,5,12,19,12); }
                case "settings", "controls" -> { if(name.equals("settings")){ g.draw(new Ellipse2D.Double(5,5,14,14)); g.draw(new Ellipse2D.Double(9,9,6,6)); for(int i=0;i<8;i++){ double a=i*Math.PI/4; path(g,12+9*Math.cos(a),12+9*Math.sin(a),12+11*Math.cos(a),12+11*Math.sin(a)); } } else { for(int i=0;i<3;i++){ int yy=5+i*7; path(g,2,yy,22,yy); g.setColor(WHITE); g.fillRect(i==1?14:6,yy-2,4,4); g.setColor(c.getForeground()); g.drawRect(i==1?14:6,yy-2,4,4); } } }
                case "chat" -> { g.drawRoundRect(3,3,18,16,7,7); path(g,5,18,4,22,10,19); }
                case "more" -> { for(int i=0;i<3;i++) g.fill(new Ellipse2D.Double(4+i*7,11,2,2)); }
                case "list" -> { for(int i=0;i<3;i++){ path(g,7,5+i*7,21,5+i*7); g.fillOval(2,4+i*7,2,2); } }
                default -> { g.drawRoundRect(4,3,16,18,2,2); path(g,8,8,16,8); path(g,8,12,16,12); path(g,8,16,13,16); }
            }
            g.dispose();
        }
        private static void path(Graphics2D g,double... p){ Path2D path=new Path2D.Double(); path.moveTo(p[0],p[1]); for(int i=2;i<p.length;i+=2) path.lineTo(p[i],p[i+1]); g.draw(path); }
    }
}
