package io.noiseless.desktop;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/** Browser-style tabs with native controls and independent retained content. */
final class ToolTabs extends JPanel {
    private record Tab(String id,String title,Icon icon,Component content) {}
    private final List<Tab> tabs=new ArrayList<>();
    private final Map<Component,Runnable> closeActions=new IdentityHashMap<>();
    private final JPanel labels=NativeTheme.chrome(new FlowLayout(FlowLayout.LEFT,4,5));
    private final JPanel pages=new JPanel(new CardLayout());
    private Component selected;
    Runnable onAdd=()->{},onHide=()->{},onMaximize=()->{},onEmpty=()->{};
    ToolTabs() {
        super(new BorderLayout()); setMinimumSize(new Dimension(0,0));
        JPanel header=NativeTheme.chrome(new BorderLayout()); header.setPreferredSize(new Dimension(0,42));
        JScrollPane scroll=new JScrollPane(labels,ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); scroll.setBorder(null); scroll.getViewport().setBackground(NativeTheme.CHROME); header.add(scroll);
        JPanel controls=NativeTheme.chrome(new FlowLayout(FlowLayout.RIGHT,1,6)); controls.add(NativeTheme.icon("plus","新标签页",()->onAdd.run())); controls.add(NativeTheme.icon("panelRight","展开 / 还原侧栏",()->onMaximize.run())); controls.add(NativeTheme.icon("close","收起侧栏",()->onHide.run())); header.add(controls,BorderLayout.EAST);
        add(header,BorderLayout.NORTH); add(pages);
    }
    void addTab(String title,Component component){ addTab(title,null,component); }
    void addTab(String title,Icon icon,Component component){
        if(indexOfComponent(component)>=0){ setSelectedComponent(component); return; }
        Tab tab=new Tab(UUID.randomUUID().toString(),title,icon,component); tabs.add(tab); pages.add(component,tab.id()); setSelectedComponent(component);
    }
    int indexOfComponent(Component component){ for(int i=0;i<tabs.size();i++) if(tabs.get(i).content()==component) return i; return -1; }
    int getTabCount(){ return tabs.size(); }
    Component getSelectedComponent(){ return selected; }
    void onClose(Component component,Runnable action){ closeActions.put(component,action); }
    void setSelectedIndex(int index){ if(index>=0&&index<tabs.size()) setSelectedComponent(tabs.get(index).content()); }
    void setSelectedComponent(Component component){ int index=indexOfComponent(component); if(index<0) return; selected=component; ((CardLayout)pages.getLayout()).show(pages,tabs.get(index).id()); paintLabels(); }
    @Override public void remove(Component component){
        int index=indexOfComponent(component); if(index<0) return;
        Runnable action=closeActions.remove(component); if(action!=null) action.run(); tabs.remove(index); pages.remove(component);
        if(tabs.isEmpty()){ selected=null; paintLabels(); onEmpty.run(); } else if(selected==component) setSelectedIndex(Math.min(index,tabs.size()-1)); else paintLabels();
    }
    private void paintLabels(){
        labels.removeAll();
        for(Tab tab:tabs){
            JPanel label=new JPanel(new BorderLayout(0,0)); label.setOpaque(true); boolean active=tab.content()==selected; label.setBackground(active?Color.WHITE:NativeTheme.CHROME); label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(active?new Color(0xe2e7e1):NativeTheme.CHROME),BorderFactory.createEmptyBorder(0,3,0,1)));
            JButton pick=NativeTheme.button(tab.title(),()->setSelectedComponent(tab.content())); pick.setIcon(tab.icon()); pick.setIconTextGap(7); pick.setForeground(NativeTheme.TEXT); pick.setPreferredSize(new Dimension(Math.min(134,Math.max(83,tab.title().length()*10+30)),28)); pick.setToolTipText(tab.title());
            JButton close=NativeTheme.icon("close","关闭标签："+tab.title(),()->remove(tab.content())); close.setPreferredSize(new Dimension(22,26)); label.add(pick); label.add(close,BorderLayout.EAST); labels.add(label);
        }
        labels.revalidate(); labels.repaint();
    }
}
