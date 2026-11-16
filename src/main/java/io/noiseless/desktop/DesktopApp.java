package io.noiseless.desktop;

import io.noiseless.server.*;
import io.noiseless.core.Article;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import javax.swing.*;
import javax.swing.tree.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.*;
import static io.noiseless.core.Json.*;

/** Native Swing controls only: no browser, WebView, Node or JavaScript runtime. */
public final class DesktopApp {
    private static final Color PAPER=NativeTheme.WHITE, INK=NativeTheme.TEXT, MUTED=NativeTheme.MUTED, ACCENT=NativeTheme.GREEN;
    private final JFrame frame=new JFrame("Noiseless · 静心精读");
    private final JLabel status=new JLabel(" "), account=new JLabel();
    private final DefaultMutableTreeNode root=new DefaultMutableTreeNode("我的资料");
    private final JTree tree=new JTree(root){ protected void paintComponent(Graphics g){ super.paintComponent(g); if(getClientProperty("noiseless.dropY") instanceof Number y){ g.setColor(ACCENT); g.fillRect(6,y.intValue(),Math.max(0,getWidth()-12),2); } } }; private final JPanel center=new JPanel(new CardLayout());
    private JSplitPane workspace; private JPanel sidebar,rail; private boolean directoryCollapsed; private int directoryWidth=202;
    private final JTextField search=new JTextField(); private final JComboBox<String> modelBox=new JComboBox<>();
    private final Set<String> collapsedFolders=new HashSet<>(); private boolean renderingTree; private long openSequence; private boolean pageHidSidebar;
    private final Map<String,JsonNode> modelSelections=new LinkedHashMap<>();
    private TaskPanel generation; private JDialog generationWindow; private JButton newArticleButton;
    private ApiClient api; private final ApiClient local; private JsonNode catalog=obj(); private String current;
    private final Map<String,ArticleView> views=new HashMap<>(); private final List<String> history=new ArrayList<>(); private int position=-1,epoch;
    private record Item(String id,String label) { @Override public String toString(){ return label; } }
    private DesktopApp(ApiClient local,ApiClient initial) {
        this.local=local; this.api=initial;
        if(System.getProperty("os.name").contains("Mac")){ frame.getRootPane().putClientProperty("apple.awt.fullWindowContent",true); frame.getRootPane().putClientProperty("apple.awt.transparentTitleBar",true); frame.getRootPane().putClientProperty("apple.awt.windowTitleVisible",false); }
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE); frame.setMinimumSize(new Dimension(1040,720)); frame.setSize(1340,860); frame.setLocationRelativeTo(null);
        JPanel shell=NativeTheme.chrome(new BorderLayout()); frame.setContentPane(shell);
        rail=NativeTheme.chrome(new BorderLayout()); rail.setPreferredSize(new Dimension(44,0));
        JPanel railTop=NativeTheme.chrome(null); railTop.setLayout(new BoxLayout(railTop,BoxLayout.Y_AXIS)); railTop.setPreferredSize(new Dimension(44,205));
        railTop.add(Box.createVerticalStrut(50));
        railTop.add(NativeTheme.icon("home","阅读 / 显示目录",()->{ if(current!=null) open(current,true); }));
        railTop.add(Box.createVerticalStrut(9)); railTop.add(NativeTheme.icon("blank","空白页",this::blank));
        railTop.add(Box.createVerticalStrut(9)); railTop.add(NativeTheme.icon("vocabulary","单词本",this::vocabulary)); rail.add(railTop,BorderLayout.NORTH);
        JPanel railBottom=NativeTheme.chrome(new FlowLayout(FlowLayout.CENTER,4,8)); railBottom.add(NativeTheme.icon("settings","设置",this::settings)); rail.add(railBottom,BorderLayout.SOUTH); shell.add(rail,BorderLayout.WEST);
        sidebar=new JPanel(new BorderLayout()); sidebar.setBackground(PAPER);
        JPanel navigation=NativeTheme.chrome(new FlowLayout(FlowLayout.LEFT,2,6)); navigation.setBorder(BorderFactory.createEmptyBorder(0,30,0,0)); navigation.setPreferredSize(new Dimension(202,42));
        navigation.add(NativeTheme.icon("back","后退",()->navigate(-1))); navigation.add(NativeTheme.icon("forward","前进",()->navigate(1))); navigation.add(NativeTheme.icon("panel","收起目录",this::toggleSidebar)); sidebar.add(navigation,BorderLayout.NORTH);
        JPanel directory=new JPanel(new BorderLayout(0,12)); directory.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,1,0,0,NativeTheme.LINE),BorderFactory.createEmptyBorder(18,12,12,12))); directory.setBackground(PAPER);
        JPanel heading=new JPanel(new BorderLayout(0,14)); JPanel brand=new JPanel(new BorderLayout()); JLabel name=new JLabel("Noiseless"); name.setFont(NativeTheme.font(14).deriveFont(Font.BOLD)); brand.add(name); brand.add(NativeTheme.icon("folderAdd","新建文件夹",this::newFolder),BorderLayout.EAST);
        JButton add=NativeTheme.button("＋  新增文章",this::newArticle); newArticleButton=add; add.setHorizontalAlignment(SwingConstants.LEFT); add.setForeground(INK); add.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xe1e4e1)),BorderFactory.createEmptyBorder(9,10,9,10))); add.setPreferredSize(new Dimension(176,36));
        heading.add(brand,BorderLayout.NORTH); heading.add(add,BorderLayout.CENTER); search.setVisible(false); heading.add(search,BorderLayout.SOUTH); directory.add(heading,BorderLayout.NORTH);
        tree.setRootVisible(false); tree.setShowsRootHandles(false); tree.setRowHeight(40); tree.setBackground(PAPER); tree.setBorder(null);
        tree.setCellRenderer((t,value,selected,expanded,leaf,row,focus)->{
            var node=(DefaultMutableTreeNode)value; var item=node.getUserObject() instanceof Item it?it:new Item("","");
            JPanel cell=new JPanel(new BorderLayout(7,0)); cell.setBackground(selected?new Color(0xf2f3f2):PAPER); cell.setBorder(BorderFactory.createEmptyBorder(6,7,6,6));
            JLabel icon=new JLabel(new NativeTheme.LineIcon(item.id().startsWith("folder:")?"folder":"book")); icon.setForeground(MUTED); cell.add(icon,BorderLayout.WEST);
            JLabel text=new DirectoryLabel(item.label(),tree,row); text.setFont(NativeTheme.font(13)); text.setForeground(INK); cell.add(text); cell.setToolTipText(item.label());
            JPanel dots=new JPanel(new FlowLayout(FlowLayout.RIGHT,2,4)); dots.setOpaque(false); for(var tag:catalog.path("library").path("articles").path(item.id()).path("tags")){ JLabel dot=new JLabel("●"); dot.setFont(NativeTheme.font(9)); dot.setForeground(tagColor(tag.asText())); dots.add(dot); } cell.add(dots,BorderLayout.EAST);
            cell.setPreferredSize(new Dimension(Math.max(100,tree.getWidth()-(node.getLevel()>1?12:0)),36)); return cell;
        });
        tree.setUI(new javax.swing.plaf.basic.BasicTreeUI(){ @Override protected int getRowX(int row,int depth){ return Math.max(0,(depth-1)*12); } });
        tree.addMouseListener(new MouseAdapter(){ public void mouseClicked(MouseEvent e){ if(e.getClickCount()!=1||!SwingUtilities.isLeftMouseButton(e)||Boolean.TRUE.equals(tree.getClientProperty("noiseless.drag"))) return; var path=tree.getPathForLocation(e.getX(),e.getY()); if(path!=null&&nodeKey((DefaultMutableTreeNode)path.getLastPathComponent()).startsWith("folder:")){ if(tree.isExpanded(path)) tree.collapsePath(path); else tree.expandPath(path); } } });
        tree.addTreeExpansionListener(new javax.swing.event.TreeExpansionListener(){ public void treeExpanded(javax.swing.event.TreeExpansionEvent e){ if(!renderingTree){ String id=nodeKey((DefaultMutableTreeNode)e.getPath().getLastPathComponent()); collapsedFolders.remove(id); } } public void treeCollapsed(javax.swing.event.TreeExpansionEvent e){ if(!renderingTree) collapsedFolders.add(nodeKey((DefaultMutableTreeNode)e.getPath().getLastPathComponent())); } });
        tree.setTransferHandler(new LibraryDrag(tree,DesktopApp::nodeKey,()->catalog,this::mutate));
        javax.swing.Timer titles=new javax.swing.Timer(32,e->{ if(tree.getClientProperty("noiseless.hoverRow") instanceof Integer row&&row>=0){ Rectangle bounds=tree.getRowBounds(row); if(bounds!=null) tree.repaint(bounds); } });
        tree.addMouseMotionListener(new MouseMotionAdapter(){ public void mouseMoved(MouseEvent e){ int row=tree.getRowForLocation(e.getX(),e.getY()); if(!Objects.equals(tree.getClientProperty("noiseless.hoverRow"),row)){ tree.putClientProperty("noiseless.hoverRow",row); tree.putClientProperty("noiseless.hoverStart",System.nanoTime()); } if(!titles.isRunning()) titles.start(); } }); tree.addMouseListener(new MouseAdapter(){ public void mouseExited(MouseEvent e){ titles.stop(); tree.putClientProperty("noiseless.hoverRow",-1); tree.repaint(); } });
        ToolTipManager.sharedInstance().registerComponent(tree); JScrollPane list=new JScrollPane(tree); list.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); directory.add(list);
        account.setFont(NativeTheme.font(10)); account.setForeground(MUTED); directory.add(account,BorderLayout.SOUTH); sidebar.add(directory);
        center.setBackground(PAPER); JPanel welcome=new JPanel(new BorderLayout()); JPanel welcomeHeader=NativeTheme.chrome(new BorderLayout()); welcomeHeader.setPreferredSize(new Dimension(0,42)); welcome.add(welcomeHeader,BorderLayout.NORTH);
        JPanel empty=new JPanel(new GridBagLayout()); JLabel invite=new JLabel("选择一篇文章，开始阅读"); invite.setFont(NativeTheme.font(14)); invite.setForeground(MUTED); empty.add(invite); welcome.add(empty); center.add(welcome,"welcome");
        workspace=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,sidebar,center); NativeTheme.split(workspace); workspace.setDividerLocation(202); sidebar.setMinimumSize(new Dimension(160,0)); shell.add(workspace,BorderLayout.CENTER);
        tree.addTreeSelectionListener(e->{ Object last=tree.getLastSelectedPathComponent(); if(!renderingTree && last instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Item item && !item.id().startsWith("folder:")) open(item.id(),true); });
        tree.addMouseListener(new MouseAdapter(){ @Override public void mousePressed(MouseEvent e){ popup(e); } @Override public void mouseReleased(MouseEvent e){ popup(e); } });
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){ public void insertUpdate(javax.swing.event.DocumentEvent e){ renderTree(); } public void removeUpdate(javax.swing.event.DocumentEvent e){ renderTree(); } public void changedUpdate(javax.swing.event.DocumentEvent e){ renderTree(); } });
        JMenuBar menu=new JMenuBar(); JMenu file=new JMenu("资料"); menu.add(file); JMenuItem change=new JMenuItem("修改云端密码"); change.addActionListener(e->changePassword()); file.add(change); JMenuItem logout=new JMenuItem("退出云端账号"); logout.addActionListener(e->{ ApiClient previous=api; if(previous==local) return; work(()->previous.call("POST","auth/logout",obj()),v->switchApi(local)); }); file.add(logout); frame.setJMenuBar(menu);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(event->{ if(event.getID()==KeyEvent.KEY_PRESSED&&event.getKeyCode()==KeyEvent.VK_ESCAPE&&KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()==frame){ var view=views.get(current); if(view!=null&&view.maximized){ view.restoreSupport(); return true; } } return false; });
        new SwipeNavigation(frame,d->position+d>=0&&position+d<history.size(),this::navigate);
        bind("alt LEFT",()->navigate(-1)); bind("alt RIGHT",()->navigate(1)); bind("meta OPEN_BRACKET",()->navigate(-1)); bind("meta CLOSE_BRACKET",()->navigate(1)); bind("ESCAPE",()->{ var view=views.get(current); if(view!=null) view.restoreSupport(); });
        frame.setVisible(true);
        if(java.awt.Desktop.isDesktopSupported()&&java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) java.awt.Desktop.getDesktop().requestForeground(true);
        frame.toFront(); frame.requestFocus(); refresh();
    }
    public static void launch(String[] args) throws Exception {
        System.setProperty("apple.laf.useScreenMenuBar","true"); UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        NativeTheme.install();
        Path directory=Path.of(System.getProperty("user.home"),".noiseless-java"); Files.createDirectories(directory);
        Server server=new Server(directory.resolve("local"),"127.0.0.1",0,AiService.Config.environment(),true,true); server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()-> { try { server.close(); } catch(Exception ignored) {} }));
        ApiClient local=new ApiClient("http://127.0.0.1:"+server.port()); Path credentials=directory.resolve("local-account.json"); JsonNode auth;
        if(Files.exists(credentials)) auth=parse(Files.readString(credentials));
        else {
            auth=obj("email","local@noiseless.local","name","本地用户","password",id()+id());
            try { Files.createFile(credentials,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))); }
            catch(UnsupportedOperationException e) { Files.createFile(credentials); }
            Files.writeString(credentials,str(auth));
        }
        JsonNode logged; try { logged=local.call("POST","auth/login",auth); } catch(Fault e) { if(e.status!=401) throw e; logged=local.call("POST","auth/register",auth); }
        local.token(logged.path("token").asText());
        String user=logged.path("user").path("id").asText();
        if(server.db.query("SELECT 1 FROM docs WHERE user_id=? AND kind='meta' AND id='seeded'",user).isEmpty()){
            if(server.db.list(user,"articles").isEmpty()){
                var samples=arr(); try(var index=DesktopApp.class.getResourceAsStream("/seed/index.txt")){ for(String name:new String(index.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split("\n")) if(!name.isBlank()) try(var in=DesktopApp.class.getResourceAsStream("/seed/"+name)){ samples.add(obj("id",name,"data",MAPPER.readTree(in))); } }
                JsonNode lib; try(var in=DesktopApp.class.getResourceAsStream("/seed/library.json")){ lib=MAPPER.readTree(in); }
                new Importer(server.db,new Library(server.db)).apply(user,obj("format","noiseless-cloud-backup-1","articles",samples,"library",lib,"vocabulary",arr(),"lexicon",arr()));
            }
            server.db.put(user,"meta","seeded",obj("done",true),0);
        }
        SwingUtilities.invokeLater(()->{ DesktopApp app=new DesktopApp(local,local); if(args.length>=3&&args[1].equals("--cloud")) app.login(args[2]); });
    }
    private static JButton button(JPanel p,String label,Runnable action) { JButton b=NativeTheme.button(label,action); p.add(b); return b; }
    private <T> void work(Callable<T> task,Consumer<T> done) {
        int stamp=epoch; status.setText("正在处理…");
        new SwingWorker<T,Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() { if(stamp!=epoch) return; try { T value=get(); status.setText("已完成"); done.accept(value); } catch(Exception e) { Throwable cause=e instanceof ExecutionException?e.getCause():e; status.setText("操作未完成"); JOptionPane.showMessageDialog(frame,cause.getMessage(),"Noiseless",JOptionPane.ERROR_MESSAGE); } }
        }.execute();
    }
    private void refresh() {
        ApiClient client=api;
        work(()->client.call("GET","library",null),v->{ catalog=v; renderTree(); account.setText(api==local?"本地资料 · 离线可用":api.base+" · 云端资料");
            if(current!=null) open(current,false); else if(!v.path("articles").isEmpty()){
                String first=v.path("library").path("order").path(0).asText(v.path("articles").path(0).path("file").asText()); open(first,true);
            } });
        work(()->client.call("GET","ai/settings",null),settings->{ String previous=(String)modelBox.getSelectedItem(); modelBox.removeAllItems(); modelSelections.clear(); for(var choice:settings.path("selections")){ modelSelections.put(choice.path("label").asText(),choice); modelBox.addItem(choice.path("label").asText()); } if(previous!=null&&modelSelections.containsKey(previous)) modelBox.setSelectedItem(previous); else for(var choice:settings.path("selections")) if(choice.path("profileId").asText().equals(settings.path("defaultProfileId").asText("server"))){ modelBox.setSelectedItem(choice.path("label").asText()); break; } if(!settings.path("configured").asBoolean()) status.setText("AI 尚未配置 · 阅读、查词和资料管理可正常使用"); });
    }
    private void renderTree() {
        renderingTree=true; root.removeAllChildren(); Map<String,DefaultMutableTreeNode> folders=new LinkedHashMap<>();
        for(var f:catalog.path("library").path("folders")) { var n=new DefaultMutableTreeNode(new Item("folder:"+f.path("id").asText(),f.path("name").asText())); folders.put(f.path("id").asText(),n); }
        List<JsonNode> articles=new ArrayList<>(); catalog.path("articles").forEach(articles::add); Map<String,Integer> order=new HashMap<>(); int i=0; for(var id:catalog.path("library").path("order")) order.put(id.asText(),i++);
        articles.sort(Comparator.comparingInt(a->order.getOrDefault(a.path("file").asText(),Integer.MAX_VALUE)));
        for(var a:articles) {
            String id=a.path("file").asText(); JsonNode meta=catalog.path("library").path("articles").path(id); String title=meta.path("name").asText(a.path("subtitle").asText().isBlank()?a.path("title").asText():a.path("subtitle").asText());
            if(!title.toLowerCase(Locale.ROOT).contains(search.getText().trim().toLowerCase(Locale.ROOT))) continue;
            String tags=meta.path("tags").isEmpty()?"":" ["+meta.path("tags").get(0).asText()+"]";
            DefaultMutableTreeNode parent=folders.getOrDefault(meta.path("folder").asText(),root); parent.add(new DefaultMutableTreeNode(new Item(id,title)));
        }
        folders.values().forEach(root::add);
        ((DefaultTreeModel)tree.getModel()).reload(); for(int row=0;row<tree.getRowCount();row++){ var path=tree.getPathForRow(row); if(!collapsedFolders.contains(nodeKey((DefaultMutableTreeNode)path.getLastPathComponent()))) tree.expandPath(path); }
        selectArticleNode(current); renderingTree=false;
    }
    private void open(String id,boolean record) {
        ApiClient client=api; long sequence=++openSequence;
        work(()->client.call("GET","articles/"+ApiClient.encode(id),null),v->{
            if(sequence!=openSequence) return; current=id; if(pageHidSidebar){ sidebar.setVisible(true); workspace.setDividerLocation(202); pageHidSidebar=false; }
            if(record) recordHistory(id);
            ArticleView existing=views.get(id);
            if(existing==null){ existing=new ArticleView(v); views.put(id,existing); center.add(existing.panel,id); }
            else if(existing.document.path("revision").asLong()!=v.path("revision").asLong()) existing.update(v);
            selectArticleNode(id);
            applyChrome(existing); ((CardLayout)center.getLayout()).show(center,id); frame.setTitle(v.path("article").path("title").asText()+" · Noiseless");
        });
    }
    private static String nodeKey(DefaultMutableTreeNode n){ return n.getUserObject() instanceof Item item?item.id():""; }
    private void selectArticleNode(String id){ if(id==null) return; boolean previous=renderingTree; renderingTree=true; var nodes=root.depthFirstEnumeration(); while(nodes.hasMoreElements()){ var n=nodes.nextElement(); if(n instanceof DefaultMutableTreeNode item&&nodeKey(item).equals(id)){ tree.setSelectionPath(new TreePath(item.getPath())); break; } } renderingTree=previous; }
    private static Color tagColor(String tag){ return new Color(switch(tag){ case "red"->0xc85c57; case "orange"->0xdb9b62; case "yellow"->0xd4ba65; case "green"->0x75a186; case "blue"->0x729bbb; case "purple"->0xa390b7; default->0xa2a6a2; }); }
    private void bind(String key,Runnable action){ String id="noiseless-"+key; frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key),id); frame.getRootPane().getActionMap().put(id,new AbstractAction(){ public void actionPerformed(ActionEvent e){ action.run(); } }); }
    private void recordHistory(String key){ if(position>=0&&history.get(position).equals(key)) return; while(history.size()>position+1) history.removeLast(); history.add(key); position=history.size()-1; }
    private void blank(){ openSequence++; recordHistory("@blank"); pageHidSidebar=true; sidebar.setVisible(false); rail.setVisible(true); workspace.setDividerLocation(0); ((CardLayout)center.getLayout()).show(center,"welcome"); }
    private void newFolder() { String name=JOptionPane.showInputDialog(frame,"文件夹名称"); if(name!=null) mutate(obj("action","create-folder","name",name)); }
    private void toggleSidebar(){ if(sidebar.isVisible()) directoryWidth=Math.max(160,workspace.getDividerLocation()); directoryCollapsed=!directoryCollapsed; var view=views.get(current); if(view!=null) applyChrome(view); }
    private void applyChrome(ArticleView view){ rail.setVisible(!view.maximized); sidebar.setVisible(!view.maximized&&!directoryCollapsed&&!pageHidSidebar); workspace.setDividerLocation(sidebar.isVisible()?directoryWidth:0); frame.revalidate(); }
    private void settings(){
        JDialog dialog=new JDialog(frame,"设置",false); JPanel shell=new JPanel(new BorderLayout()); DefaultListModel<String> items=new DefaultListModel<>(); for(String label:List.of("AI 模型","账号与云服务","资料管理","关于")) items.addElement(label);
        JList<String> sections=new JList<>(items); sections.setFixedCellHeight(42); sections.setBackground(NativeTheme.CHROME); sections.setSelectionBackground(new Color(0xe8eee9)); sections.setSelectionForeground(INK); sections.setBorder(BorderFactory.createEmptyBorder(24,14,24,14)); sections.setPreferredSize(new Dimension(170,0)); shell.add(sections,BorderLayout.WEST);
        JPanel pages=new JPanel(new CardLayout()); shell.add(pages); JPanel models=new JPanel(new BorderLayout(0,18)); models.setBorder(BorderFactory.createEmptyBorder(26,28,24,28)); JLabel heading=new JLabel("模型"); heading.setFont(NativeTheme.font(24).deriveFont(Font.BOLD)); models.add(heading,BorderLayout.NORTH); JPanel cards=new JPanel(); cards.setLayout(new BoxLayout(cards,BoxLayout.Y_AXIS)); models.add(new JScrollPane(cards));
        Runnable reload=()->{ ApiClient client=api; work(()->client.call("GET","ai/settings",null),value->{ cards.removeAll();
            JPanel server=new JPanel(new BorderLayout(8,8)); server.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(NativeTheme.LINE),BorderFactory.createEmptyBorder(14,16,14,16))); server.setMaximumSize(new Dimension(Integer.MAX_VALUE,130)); server.add(new JLabel("服务器提供的模型"),BorderLayout.NORTH); server.add(new JLabel(value.path("configured").asBoolean()?value.path("models").toString():"尚未配置")); server.add(NativeTheme.button("设为默认",()->work(()->client.call("POST","ai/default",obj("profileId","server")),v->refresh())),BorderLayout.EAST); cards.add(server); cards.add(Box.createVerticalStrut(12));
            for(var profile:value.path("profiles")){ JPanel card=new JPanel(new BorderLayout(0,9)); card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(NativeTheme.LINE),BorderFactory.createEmptyBorder(14,16,14,16))); card.setMaximumSize(new Dimension(Integer.MAX_VALUE,160)); JLabel name=new JLabel(profile.path("name").asText()+(profile.path("id").asText().equals(value.path("defaultProfileId").asText())?" · 默认":"")); name.setFont(NativeTheme.font(14).deriveFont(Font.BOLD)); card.add(name,BorderLayout.NORTH);
                JPanel detail=new JPanel(new GridLayout(0,1,0,5)); JLabel address=new JLabel(profile.path("baseUrl").asText()); address.setForeground(MUTED); detail.add(address); detail.add(new JLabel(profile.path("models").toString()+(profile.path("manualModel").asBoolean()?" · 手动模型，未验证":""))); card.add(detail);
                JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0)); button(actions,"编辑",()->providerEditor(profile)); button(actions,"设为默认",()->work(()->client.call("POST","ai/default",obj("profileId",profile.path("id"))),v->refresh())); button(actions,"删除",()->{ if(JOptionPane.showConfirmDialog(dialog,"删除供应商配置？","确认",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION) work(()->client.call("DELETE","ai/profiles/"+profile.path("id").asText(),obj()),v->{ dialog.dispose(); refresh(); settings(); }); }); card.add(actions,BorderLayout.SOUTH); cards.add(card); cards.add(Box.createVerticalStrut(12));
            } cards.revalidate(); cards.repaint(); }); };
        JPanel modelFooter=new JPanel(new BorderLayout()); JButton add=NativeTheme.button("＋ 添加供应商",()->providerEditor(null)); add.setForeground(ACCENT); modelFooter.add(add,BorderLayout.WEST); modelFooter.add(NativeTheme.button("刷新",reload),BorderLayout.EAST); models.add(modelFooter,BorderLayout.SOUTH); pages.add(models,"AI 模型");
        JPanel cloud=settingsPage("账号与云服务"); cloud.add(new JLabel(account.getText())); button(cloud,"登录 / 注册",this::login); button(cloud,"切换本地资料",()->switchApi(local)); button(cloud,"本地资料上传云端",this::uploadLocal); button(cloud,"修改密码",this::changePassword); button(cloud,"退出登录",()->{ ApiClient previous=api; if(previous!=local) work(()->previous.call("POST","auth/logout",obj()),v->switchApi(local)); }); pages.add(cloud,"账号与云服务");
        JPanel data=settingsPage("资料管理"); button(data,"导入资料 / 旧版迁移",this::importFiles); button(data,"导出资料",this::export); button(data,"用户词库",this::userLexicon); button(data,"附件管理",()->manageFiles(api)); button(data,"回收站",this::trash); button(data,"导入示例",this::seed); button(data,"搜索文章",()->{ String query=JOptionPane.showInputDialog(dialog,"搜索文章",search.getText()); if(query!=null) search.setText(query); }); pages.add(data,"资料管理");
        JPanel about=settingsPage("Noiseless"); about.add(new JLabel("静心阅读，理解语言。")); about.add(new JLabel("Java 原生桌面端 · 1.1")); pages.add(about,"关于"); sections.addListSelectionListener(e->{ if(!e.getValueIsAdjusting()) ((CardLayout)pages.getLayout()).show(pages,sections.getSelectedValue()); }); sections.setSelectedIndex(0); dialog.setContentPane(shell); dialog.setSize(920,650); dialog.setLocationRelativeTo(frame); dialog.setVisible(true); reload.run();
    }
    private JPanel settingsPage(String title){ JPanel p=new JPanel(); p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS)); p.setBorder(BorderFactory.createEmptyBorder(30,30,30,30)); JLabel h=new JLabel(title); h.setFont(NativeTheme.font(24).deriveFont(Font.BOLD)); p.add(h); p.add(Box.createVerticalStrut(24)); return p; }
    private void providerEditor(JsonNode existing){
        ApiClient client=api; JDialog dialog=new JDialog(frame,existing==null?"添加供应商":"编辑供应商",false); JPanel panel=new JPanel(new BorderLayout(0,16)); panel.setBorder(BorderFactory.createEmptyBorder(24,28,20,28)); JLabel heading=new JLabel(existing==null?"添加供应商":"编辑供应商"); heading.setFont(NativeTheme.font(20).deriveFont(Font.BOLD)); panel.add(heading,BorderLayout.NORTH);
        JPanel fields=new JPanel(new GridLayout(0,1,0,6)); JComboBox<String> provider=new JComboBox<>(new String[]{"DeepSeek","Kimi","通义千问","智谱 GLM","硅基流动","OpenAI","Anthropic","Gemini","自定义"}); JTextField name=new JTextField(existing==null?"":existing.path("name").asText()),base=new JTextField(existing==null?"https://api.deepseek.com":existing.path("baseUrl").asText()); JPasswordField secret=new JPasswordField(); secret.setToolTipText(existing==null?"输入 API Key":"密钥已保存；不更改连接时可留空"); JComboBox<String> protocol=new JComboBox<>(new String[]{"chat","responses","anthropic","gemini"}); protocol.setSelectedItem(existing==null?"chat":existing.path("protocol").asText()); JTextArea ids=new JTextArea(4,30); ids.setLineWrap(true); if(existing!=null){ List<String> models=new ArrayList<>(); existing.path("models").forEach(m->models.add(m.asText())); ids.setText(String.join("\n",models)); }
        JCheckBox manual=new JCheckBox("手动填写模型 ID（未验证）",existing==null||existing.path("manualModel").asBoolean()),makeDefault=new JCheckBox("设为默认供应商",false); JLabel error=new JLabel(" "); error.setForeground(new Color(0xab372f));
        for(Component c:new Component[]{new JLabel("供应商"),provider,new JLabel("配置名称"),name,new JLabel("Base URL"),base,new JLabel("协议"),protocol,new JLabel("API Key"),secret,new JLabel("模型 ID（每行一个）"),new JScrollPane(ids),manual,makeDefault,error}) fields.add(c);
        provider.addActionListener(e->{ String choice=(String)provider.getSelectedItem(); if(name.getText().isBlank()) name.setText(choice); String url=switch(choice){ case "DeepSeek"->"https://api.deepseek.com"; case "Kimi"->"https://api.moonshot.cn/v1"; case "通义千问"->"https://dashscope.aliyuncs.com/compatible-mode/v1"; case "智谱 GLM"->"https://open.bigmodel.cn/api/paas/v4"; case "硅基流动"->"https://api.siliconflow.cn/v1"; case "OpenAI"->"https://api.openai.com/v1"; case "Anthropic"->"https://api.anthropic.com/v1"; case "Gemini"->"https://generativelanguage.googleapis.com/v1beta"; default->base.getText(); }; base.setText(url); protocol.setSelectedItem(choice.equals("OpenAI")?"responses":choice.equals("Anthropic")?"anthropic":choice.equals("Gemini")?"gemini":"chat"); });
        java.util.function.Supplier<ObjectNode> body=()->{ var b=obj("name",name.getText(),"provider",provider.getSelectedItem(),"baseUrl",base.getText(),"protocol",protocol.getSelectedItem(),"manualModel",manual.isSelected(),"makeDefault",makeDefault.isSelected()); if(existing!=null) b.put("id",existing.path("id").asText()); if(secret.getPassword().length>0) b.put("apiKey",new String(secret.getPassword())); var models=arr(); for(String model:ids.getText().split("[\n,]")) if(!model.isBlank()) models.add(model.trim()); b.set("models",models); return b; };
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT)); button(actions,"获取模型",()->work(()->client.call("POST","ai/models",body.get()),models->{ List<String> values=new ArrayList<>(); models.forEach(m->values.add(m.asText())); ids.setText(String.join("\n",values)); manual.setSelected(false); })); button(actions,"保存",()->{ var b=body.get(); if(!manual.isSelected()&&ids.getText().isBlank()){ error.setText("请先获取模型或手动填写 ID"); return; } work(()->client.call("PUT","ai/profiles",b),v->{ dialog.dispose(); refresh(); }); }); panel.add(new JScrollPane(fields)); panel.add(actions,BorderLayout.SOUTH); dialog.setContentPane(panel); dialog.setSize(650,760); dialog.setLocationRelativeTo(frame); dialog.setVisible(true);
    }
    private void navigate(int delta) { int next=position+delta; if(next>=0&&next<history.size()) { position=next; String target=history.get(position); if(target.equals("@blank")) ((CardLayout)center.getLayout()).show(center,"welcome"); else if(target.equals("@vocabulary")) vocabulary(false); else open(target,false); } }
    private void mutate(JsonNode body) { if(body.has("folder")) collapsedFolders.remove("folder:"+body.path("folder").asText()); ApiClient client=api; work(()->client.call("POST","library/actions",body),v->refresh()); }
    private void popup(MouseEvent e) {
        if(!e.isPopupTrigger()) return; TreePath path=tree.getPathForLocation(e.getX(),e.getY()); if(path==null) return;
        var node=(DefaultMutableTreeNode)path.getLastPathComponent(); if(!(node.getUserObject() instanceof Item item)) return;
        JPopupMenu menu=new JPopupMenu(); boolean folder=item.id().startsWith("folder:"); String id=folder?item.id().substring(7):item.id();
        menu(menu,"重命名",()->{ String name=JOptionPane.showInputDialog(frame,"新名称"); if(name!=null) mutate(folder?obj("action","rename-folder","id",id,"name",name):obj("action","update-article","file",id,"name",name)); });
        if(folder) menu(menu,"解散文件夹",()->mutate(obj("action","remove-folder","id",id)));
        else {
            menu(menu,"移动到文件夹",()-> {
                List<Item> items=new ArrayList<>(); items.add(new Item("","根目录")); for(var f:catalog.path("library").path("folders")) items.add(new Item(f.path("id").asText(),f.path("name").asText()));
                Item choice=(Item)JOptionPane.showInputDialog(frame,"选择分类","分类",JOptionPane.PLAIN_MESSAGE,null,items.toArray(),items.getFirst()); if(choice!=null) mutate(obj("action","move-article","file",id,"folder",choice.id()));
            });
            menu(menu,"颜色标签",()->{ String color=(String)JOptionPane.showInputDialog(frame,"选择标签（clear 清除）","颜色",JOptionPane.PLAIN_MESSAGE,null,new String[]{"red","orange","yellow","green","blue","purple","gray","clear"},"green"); if(color!=null) mutate(obj("action","update-article","file",id,"tags",color.equals("clear")?arr():arr().add(color))); });
            menu(menu,"移到本组最前",()-> {
                String folderId=catalog.path("library").path("articles").path(id).path("folder").asText(); String first=null;
                for(var a:catalog.path("articles")) { String f=a.path("file").asText(); if(!f.equals(id)&&catalog.path("library").path("articles").path(f).path("folder").asText().equals(folderId)) { first=f; break; } }
                var b=obj("action","move-article","file",id,"folder",folderId); if(first!=null) b.put("before",first); mutate(b);
            });
            menu(menu,"删除文章",()-> {
                if(JOptionPane.showConfirmDialog(frame,"删除文章？可在回收站恢复。","确认删除",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
                ApiClient client=api; work(()-> { var d=client.call("GET","articles/"+ApiClient.encode(id),null); return client.call("DELETE","articles/"+ApiClient.encode(id),obj("revision",d.path("revision"))); },v->{ if(id.equals(current)) { current=null; ((CardLayout)center.getLayout()).show(center,"welcome"); } ArticleView view=views.remove(id); if(view!=null) { view.dispose(); center.remove(view.panel); } refresh(); });
            });
        }
        menu.show(tree,e.getX(),e.getY());
    }
    private static void menu(JPopupMenu menu,String name,Runnable action) { JMenuItem i=new JMenuItem(name); i.addActionListener(e->action.run()); menu.add(i); }
    private void switchApi(ApiClient next) { for(Window window:frame.getOwnedWindows()) window.dispose(); epoch++; openSequence++; if(generation!=null) generation.cancel(); views.values().forEach(ArticleView::dispose); views.clear(); center.removeAll(); center.add(new JLabel("选择文章开始阅读",SwingConstants.CENTER),"welcome"); current=null; history.clear(); position=-1; api=next; refresh(); }
    private void login() { login("https://"); }
    private void login(String url) {
        JTextField endpoint=new JTextField(url), email=new JTextField(), name=new JTextField(); JPasswordField password=new JPasswordField(); JCheckBox register=new JCheckBox("注册新账号");
        JPanel fields=new JPanel(new GridLayout(0,1,4,6)); for(Component c:new Component[]{new JLabel("云端服务地址"),endpoint,new JLabel("邮箱"),email,new JLabel("密码（注册至少 10 位）"),password,new JLabel("名称（注册时填写）"),name,register}) fields.add(c);
        if(JOptionPane.showConfirmDialog(frame,fields,"云端账号",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
        work(()->{ ApiClient client=new ApiClient(endpoint.getText().trim()); try { var response=client.call("POST",register.isSelected()?"auth/register":"auth/login",obj("email",email.getText(),"password",new String(password.getPassword()),"name",name.getText())); client.token(response.path("token").asText()); return client; } catch(Exception e) { client.close(); throw e; } },this::switchApi);
    }
    private void changePassword() {
        if(api==local) { JOptionPane.showMessageDialog(frame,"本地自动账号由应用管理，请先连接云端账号。"); return; }
        JPasswordField old=new JPasswordField(), next=new JPasswordField(); JPanel p=new JPanel(new GridLayout(0,1)); p.add(new JLabel("旧密码")); p.add(old); p.add(new JLabel("新密码（至少 10 位）")); p.add(next);
        if(JOptionPane.showConfirmDialog(frame,p,"修改密码",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
        ApiClient client=api; work(()->client.call("POST","auth/password",obj("oldPassword",new String(old.getPassword()),"password",new String(next.getPassword()))),v->{ switchApi(local); JOptionPane.showMessageDialog(frame,"密码已修改，所有云端登录已失效，请重新登录。"); });
    }
    private void importFiles() {
        JFileChooser picker=new JFileChooser(); picker.setMultiSelectionEnabled(true); picker.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if(picker.showOpenDialog(frame)!=JFileChooser.APPROVE_OPTION) return; var files=picker.getSelectedFiles(); ApiClient client=api;
        work(()-> {
            if(files.length==1 && files[0].isDirectory()) {
                var response=client.call("POST","import",LegacyImport.read(files[0].toPath()));
                return "已迁移 "+response.path("imported").asInt()+" 篇文章及文件夹、颜色、排序、单词本和用户词库。";
            }
            if(files.length==1 && files[0].isFile() && files[0].getName().endsWith(".json")) {
                var candidate=parse(Files.readString(files[0].toPath()));
                if(candidate.path("format").asText().equals("noiseless-cloud-backup-1")) {
                    var response=client.call("POST","import",candidate); return "已恢复 "+response.path("imported").asInt()+" 篇文章与关联资料。";
                }
            }
            List<Path> paths=new ArrayList<>(); for(var file:files) { if(file.isDirectory()) { try(var walk=Files.walk(file.toPath())) { paths.addAll(walk.filter(p->p.toString().endsWith(".article.json")).toList()); } } else paths.add(file.toPath()); }
            int added=0; List<String> errors=new ArrayList<>();
            for(Path path:paths) try { require(Files.size(path)<=2_000_000,"文件超过 2MB"); var a=Article.validate(parse(Files.readString(path))); client.call("POST","articles",a); added++; } catch(Exception e) { errors.add(path.getFileName()+": "+e.getMessage()); }
            return "导入 "+added+" 篇"+(errors.isEmpty()?"":"\n"+String.join("\n",errors));
        },v->{ JOptionPane.showMessageDialog(frame,v); refresh(); });
    }
    private void seed() {
        ApiClient client=api; work(()-> { int count=0; try(var in=getClass().getResourceAsStream("/seed/index.txt")) {
            for(String file:new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split("\n")) if(!file.isBlank()) {
                try(var data=getClass().getResourceAsStream("/seed/"+file)) { client.call("POST","articles",MAPPER.readTree(data)); count++; }
            }
        } return count; },v->{ JOptionPane.showMessageDialog(frame,"已导入 "+v+" 篇示例"); refresh(); });
    }
    private void export() {
        JFileChooser picker=new JFileChooser(); picker.setSelectedFile(new java.io.File("noiseless-export.json")); if(picker.showSaveDialog(frame)!=JFileChooser.APPROVE_OPTION) return;
        Path destination=picker.getSelectedFile().toPath(); if(Files.exists(destination)&&JOptionPane.showConfirmDialog(frame,"覆盖现有文件？","确认",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
        ApiClient client=api; work(()-> { var data=client.call("GET","export",null); Files.writeString(destination,pretty(data)); return destination; },v->JOptionPane.showMessageDialog(frame,"已导出到 "+v+"\n附件请使用服务器完整备份保存。"));
    }
    private void trash() {
        ApiClient client=api; work(()->client.call("GET","trash",null),rows->{ List<Item> items=new ArrayList<>(); for(var r:rows) items.add(new Item(r.path("id").asText(),r.path("data").path("data").path("title").asText()));
            if(items.isEmpty()) { JOptionPane.showMessageDialog(frame,"回收站为空"); return; }
            Item choice=(Item)JOptionPane.showInputDialog(frame,"选择要恢复的文章","回收站",JOptionPane.PLAIN_MESSAGE,null,items.toArray(),items.getFirst());
            if(choice!=null) work(()->client.call("POST","trash/"+ApiClient.encode(choice.id())+"/restore",obj()),v->refresh()); });
    }
    private void vocabulary(){ vocabulary(true); }
    private void vocabulary(boolean record){
        ApiClient client=api; work(()->client.call("GET","vocabulary",null),data->{
            JPanel page=new JPanel(new BorderLayout()); JPanel header=NativeTheme.chrome(new BorderLayout()); header.setPreferredSize(new Dimension(0,42)); JLabel caption=new JLabel("单词本",new NativeTheme.LineIcon("vocabulary"),SwingConstants.LEFT); caption.setBorder(BorderFactory.createEmptyBorder(0,24,0,0)); header.add(caption); page.add(header,BorderLayout.NORTH);
            JPanel content=new JPanel(); content.setLayout(new BoxLayout(content,BoxLayout.Y_AXIS)); content.setBorder(BorderFactory.createEmptyBorder(46,72,60,72)); JLabel heading=new JLabel("单词本"); heading.setFont(NativeTheme.font(29).deriveFont(Font.BOLD)); content.add(heading); content.add(Box.createVerticalStrut(32));
            if(data.path("words").isEmpty()){ JLabel empty=new JLabel("还没有收藏单词"); empty.setForeground(MUTED); content.add(empty); content.add(Box.createVerticalStrut(10)); content.add(new JLabel("在文章中右键单词，或在右侧释义栏中加入。")); }
            for(var word:data.path("words")){
                JPanel row=new JPanel(new BorderLayout(0,9)); row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,NativeTheme.LINE),BorderFactory.createEmptyBorder(22,0,22,0))); row.setMaximumSize(new Dimension(900,220)); row.setAlignmentX(Component.LEFT_ALIGNMENT);
                JPanel top=new JPanel(new BorderLayout()); JLabel w=new JLabel(word.path("word").asText()); w.setFont(NativeTheme.font(22).deriveFont(Font.BOLD)); top.add(w); top.add(NativeTheme.icon("close","移除单词："+word.path("word").asText(),()->work(()->client.call("POST","vocabulary/remove",obj("id",word.path("id"))),v->vocabulary(false))),BorderLayout.EAST); row.add(top,BorderLayout.NORTH);
                JsonNode entry=obj(); for(var source:word.path("sources")) if(source.path("entry").has("meaning")){ entry=source.path("entry"); break; }
                JPanel detail=new JPanel(); detail.setLayout(new BoxLayout(detail,BoxLayout.Y_AXIS)); if(!entry.path("ipa").asText().isBlank()){ JLabel ipa=new JLabel("/"+entry.path("ipa").asText()+"/"); ipa.setForeground(MUTED); detail.add(ipa); }
                JLabel meaning=new JLabel(entry.path("pos").asText()+"  "+entry.path("meaning").asText(entry.path("context").asText("原资料未提供释义"))); detail.add(meaning);
                JPanel sources=new JPanel(new FlowLayout(FlowLayout.LEFT,4,8)); for(var source:word.path("sources")){ JButton link=NativeTheme.button(source.path("title").asText()+(!source.path("available").asBoolean()?" · 暂不可用":""),()->open(source.path("file").asText(),true)); link.setEnabled(source.path("available").asBoolean()); link.setIcon(new NativeTheme.LineIcon("blank")); sources.add(link); } detail.add(sources); row.add(detail); content.add(row);
            }
            JPanel aligned=new JPanel(new BorderLayout()); aligned.add(content,BorderLayout.NORTH); JScrollPane scroll=new JScrollPane(aligned); scroll.getVerticalScrollBar().setUnitIncrement(20); page.add(scroll);
            for(Component c:center.getComponents()) if(c.getName()!=null&&c.getName().equals("vocabulary")) center.remove(c); page.setName("vocabulary"); center.add(page,"vocabulary"); ((CardLayout)center.getLayout()).show(center,"vocabulary");
            if(sidebar.isVisible()){ pageHidSidebar=true; sidebar.setVisible(false); workspace.setDividerLocation(0); } if(record) recordHistory("@vocabulary");
        });
    }
    private void uploadLocal() {
        if(api==local) { JOptionPane.showMessageDialog(frame,"请先连接云端账号。"); return; }
        ApiClient cloud=api;
        work(()->cloud.call("POST","import",local.call("GET","export",null)),result->{ JOptionPane.showMessageDialog(frame,"已将本地 "+result.path("imported").asInt()+" 篇文章及关联资料追加到云端。"); refresh(); });
    }
    private void userLexicon() {
        ApiClient client=api; work(()->client.call("GET","lexicon/user",null),rows->showJson("用户云词库 · 与单词本独立",rows,false,null));
    }
    private void showJson(String title,JsonNode value,boolean editable,Consumer<JsonNode> save) {
        JDialog dialog=new JDialog(frame,title,false); JTextArea text=new JTextArea(pretty(value)); text.setEditable(editable); text.setFont(new Font(Font.MONOSPACED,Font.PLAIN,14));
        JPanel panel=new JPanel(new BorderLayout(8,8)); panel.setBorder(BorderFactory.createEmptyBorder(12,12,12,12)); panel.add(new JScrollPane(text));
        if(editable) { JPanel actions=new JPanel(); button(actions,"校验并保存",()->{ try { save.accept(parse(text.getText())); } catch(Exception e) { JOptionPane.showMessageDialog(dialog,e.getMessage()); } }); panel.add(actions,BorderLayout.SOUTH); }
        dialog.setContentPane(panel); dialog.setSize(820,680); dialog.setLocationRelativeTo(frame); dialog.setVisible(true);
    }
    private void newArticle(){
        if(generation!=null&&!generation.finished){ if(generationWindow!=null){ generationWindow.setVisible(true); generationWindow.toFront(); } return; }
        ApiClient client=api; JDialog dialog=new JDialog(frame,"新增文章",false); JPanel shell=new JPanel(new BorderLayout(0,16)); shell.setBorder(BorderFactory.createEmptyBorder(24,28,20,28)); JLabel heading=new JLabel("新增文章"); heading.setFont(NativeTheme.font(23).deriveFont(Font.BOLD)); shell.add(heading,BorderLayout.NORTH);
        JPanel content=new JPanel(new BorderLayout(0,16)); JPanel types=new JPanel(new GridLayout(2,3,8,8)); ButtonGroup group=new ButtonGroup(); String[] selectedType={"reading"}; String[] typeIds={"reading","cloze","matching","listening","translation","writing"},names={"阅读理解","选词填空","长篇匹配","听力","段落翻译","写作"};
        for(int i=0;i<typeIds.length;i++){ String id=typeIds[i]; JToggleButton button=new JToggleButton(names[i]); button.setFont(NativeTheme.font(13)); button.setSelected(i==0); button.addActionListener(e->selectedType[0]=id); group.add(button); types.add(button); } content.add(types,BorderLayout.NORTH);
        DefaultListModel<JsonNode> attachments=new DefaultListModel<>(); JList<JsonNode> files=new JList<>(attachments); files.setFixedCellHeight(42); files.setCellRenderer((list,value,index,selected,focus)->{ JLabel label=new JLabel("  "+value.path("name").asText()+"   ·   "+value.path("size").asLong()/1024+" KB"); label.setOpaque(true); label.setBackground(selected?new Color(0xeaf2ed):PAPER); label.setFont(NativeTheme.font(12)); return label; });
        JPanel upload=new JPanel(new BorderLayout(0,10)); JPanel fileActions=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));
        button(fileActions,"＋ 选择文件",()->{
            JFileChooser picker=new JFileChooser(); picker.setMultiSelectionEnabled(true); if(picker.showOpenDialog(dialog)!=JFileChooser.APPROVE_OPTION) return; var picked=picker.getSelectedFiles(); if(attachments.size()+picked.length>10){ JOptionPane.showMessageDialog(dialog,"一次最多选择 10 个文件"); return; }
            work(()->{ var saved=arr(); for(var file:picked){ AttachmentFile data=AttachmentFile.read(file.toPath()); saved.add(client.call("POST","files",obj("name",data.name(),"mime",data.mime(),"data",Base64.getEncoder().encodeToString(data.bytes())))); } return saved; },rows->rows.forEach(attachments::addElement));
        });
        button(fileActions,"移除",()->{ int index=files.getSelectedIndex(); if(index>=0) attachments.remove(index); }); button(fileActions,"上移",()->moveAttachment(files,attachments,-1)); button(fileActions,"下移",()->moveAttachment(files,attachments,1));
        upload.add(fileActions,BorderLayout.NORTH); JScrollPane list=new JScrollPane(files); list.setPreferredSize(new Dimension(0,140)); upload.add(list);
        JTextArea source=new JTextArea(7,35); source.setLineWrap(true); source.setWrapStyleWord(true); source.setMargin(new Insets(12,12,12,12)); source.setToolTipText("粘贴文章、题目及参考答案。也可只选择图片、PDF 或文档。"); JPanel material=new JPanel(new BorderLayout(0,12)); material.add(upload,BorderLayout.NORTH); material.add(new JScrollPane(source)); JLabel note=new JLabel("图片 / PDF / 文档 / 文本 · 单个 10MB · 整理前不会调用模型"); note.setFont(NativeTheme.font(11)); note.setForeground(MUTED); material.add(note,BorderLayout.SOUTH); content.add(material); shell.add(content);
        JPanel footer=new JPanel(new BorderLayout()); JComboBox<String> picker=new JComboBox<>(); for(int i=0;i<modelBox.getItemCount();i++) picker.addItem(modelBox.getItemAt(i)); picker.setSelectedItem(modelBox.getSelectedItem()); picker.setPreferredSize(new Dimension(260,30)); footer.add(picker,BorderLayout.WEST); JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT)); button(actions,"取消",dialog::dispose);
        JButton start=button(actions,"开始整理",()->{
            if(source.getText().isBlank()&&attachments.isEmpty()){ JOptionPane.showMessageDialog(dialog,"请先选择文件或粘贴资料"); return; } var ids=arr(); for(int i=0;i<attachments.size();i++) ids.add(attachments.get(i).path("id").asText()); var request=obj("kind","generate","type",selectedType[0],"text",source.getText(),"attachmentIds",ids); if(picker.getSelectedItem()!=null){ var choice=modelSelections.get(picker.getSelectedItem().toString()); if(choice!=null){ request.put("profileId",choice.path("profileId").asText()); request.put("model",choice.path("model").asText()); } }
            generation=new TaskPanel(client,request,result->{ newArticleButton.setText("＋  新增文章"); previewGenerated(client,result.path("article"),null,0); }); generation.observer=value->{ if(value.path("status").asText().equals("cancelled")||value.path("status").asText().equals("failed")) newArticleButton.setText("＋  新增文章"); }; generation.onFailure=message->newArticleButton.setText("＋  新增文章"); newArticleButton.setText("整理中 · 查看进度"); generationWindow=new JDialog(frame,"整理文章",false); generationWindow.add(generation.panel); generationWindow.setSize(680,480); generationWindow.setLocationRelativeTo(frame); generationWindow.setVisible(true); generation.start(); dialog.dispose();
        }); start.setForeground(ACCENT); footer.add(actions,BorderLayout.EAST); shell.add(footer,BorderLayout.SOUTH); dialog.setContentPane(shell); dialog.setSize(800,660); dialog.setLocationRelativeTo(frame); dialog.setVisible(true);
    }
    private static void moveAttachment(JList<JsonNode> list,DefaultListModel<JsonNode> model,int delta){ int index=list.getSelectedIndex(),next=index+delta; if(index>=0&&next>=0&&next<model.size()){ JsonNode item=model.remove(index); model.add(next,item); list.setSelectedIndex(next); } }
    private void editArticle(ApiClient client,JsonNode document){
        final ArticleEditor[] editor=new ArticleEditor[1]; long revision=document.path("revision").asLong(); String file=document.path("file").asText();
        editor[0]=new ArticleEditor(frame,"编辑正文",document.path("article"),value->{
            new SwingWorker<JsonNode,Void>(){ protected JsonNode doInBackground() throws Exception{ return client.call("PUT","articles/"+ApiClient.encode(file),obj("article",value,"revision",revision)); }
                protected void done(){ try{ var saved=get(); editor[0].dispose(); var view=views.get(file); if(view!=null) view.update(saved); refresh(); }catch(Exception e){ Throwable cause=e instanceof ExecutionException?e.getCause():e; editor[0].error(cause.getMessage()); } }
            }.execute();
        }); editor[0].setVisible(true);
    }
    private void manageFiles(ApiClient client) {
        work(()->client.call("GET","files",null),rows->{ List<Item> choices=new ArrayList<>(); for(var f:rows) choices.add(new Item(f.path("id").asText(),f.path("name").asText()+" ("+f.path("size").asLong()+" bytes)"));
            if(choices.isEmpty()) { JOptionPane.showMessageDialog(frame,"没有附件"); return; }
            Item picked=(Item)JOptionPane.showInputDialog(frame,"选择需要清理的附件","附件管理",JOptionPane.PLAIN_MESSAGE,null,choices.toArray(),choices.getFirst());
            if(picked!=null&&JOptionPane.showConfirmDialog(frame,"删除此附件？引用此附件的音频将不可用。","确认",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION) work(()->client.call("DELETE","files/"+ApiClient.encode(picked.id()),obj()),v->status.setText("附件已删除"));
        });
    }
    private void previewGenerated(ApiClient client,JsonNode article,String file,long revision){
        final ArticleEditor[] editor=new ArticleEditor[1]; editor[0]=new ArticleEditor(frame,file==null?"整理完成 · 核对后添加":"预览修改 · 核对后保存",article,value->{
            new SwingWorker<JsonNode,Void>(){ protected JsonNode doInBackground() throws Exception{ return client.call(file==null?"POST":"PUT",file==null?"articles":"articles/"+ApiClient.encode(file),file==null?value:obj("article",value,"revision",revision)); }
                protected void done(){ try{ var saved=get(); editor[0].dispose(); refresh(); open(saved.path("file").asText(),true); }catch(Exception e){ Throwable cause=e instanceof ExecutionException?e.getCause():e; editor[0].error(cause.getMessage()); } }
            }.execute();
        }); editor[0].setVisible(true);
    }
    private final class TaskPanel {
        final JPanel panel=new JPanel(new BorderLayout(10,10)); final JLabel message=new JLabel("准备请求…"); final JTextArea output=new JTextArea();
        final ApiClient client; final JsonNode request; final Consumer<JsonNode> done; final int stamp=epoch;
        Consumer<JsonNode> observer=value->{}; Consumer<String> onFailure=message->{}; Runnable onStarted=()->{};
        String job; boolean finished, completed, polling, stopRequested; javax.swing.Timer timer;
        TaskPanel(ApiClient client,JsonNode request,Consumer<JsonNode> done) {
            this.client=client; ObjectNode selected=(ObjectNode)request.deepCopy(); if(!selected.has("model")&&modelBox.getSelectedItem()!=null){ JsonNode choice=modelSelections.get(modelBox.getSelectedItem().toString()); if(choice!=null){ selected.put("model",choice.path("model").asText()); selected.put("profileId",choice.path("profileId").asText()); } } this.request=selected; this.done=done; panel.setBorder(BorderFactory.createEmptyBorder(12,12,12,12)); panel.add(message,BorderLayout.NORTH); output.setEditable(false); output.setLineWrap(true); output.setWrapStyleWord(true); panel.add(new JScrollPane(output));
            JPanel actions=new JPanel(); button(actions,"停止",this::cancel); panel.add(actions,BorderLayout.SOUTH);
        }
        void start() {
            new SwingWorker<JsonNode,Void>() {
                protected JsonNode doInBackground() throws Exception { return client.call("POST","ai/jobs",request); }
                protected void done() { try { var value=get(); job=value.path("id").asText(); onStarted.run(); if(stopRequested||stamp!=epoch) TaskPanel.this.cancel(); else { timer=new javax.swing.Timer(900,e->poll()); timer.start(); poll(); } } catch(Exception e) { fail(e); } }
            }.execute();
        }
        void poll() {
            if(finished||polling) return; if(stamp!=epoch) { cancel(); return; } polling=true;
            new SwingWorker<JsonNode,Void>() {
                protected JsonNode doInBackground() throws Exception { return client.call("GET","ai/jobs/"+job,null); }
                protected void done() {
                    polling=false;
                    try {
                        var value=get().path("data"); observer.accept(value); String state=value.path("status").asText(); message.setText(switch(state) { case "queued" -> "等待处理"; case "running" -> "正在生成 · "+value.path("outputChars").asInt()+" 字符"; case "complete" -> "已完成"; case "cancelled" -> "已停止"; default -> "处理失败"; });
                        if(state.equals("running")&&value.has("output")) output.setText(value.path("output").asText());
                        if(Set.of("complete","failed","cancelled").contains(state)) {
                            finished=true; if(timer!=null) timer.stop();
                            if(state.equals("complete") && stamp==epoch) { completed=true; var result=value.path("result"); output.setText(result.path("text").asText(result.has("translation")?result.path("translation").asText():"草稿已生成，请在预览窗口核对。")); done.accept(result); }
                            else if(state.equals("failed")) output.setText(value.path("error").asText());
                        }
                    } catch(Exception e) { fail(e); }
                }
            }.execute();
        }
        void cancel() {
            stopRequested=true; if(timer!=null) timer.stop(); if(job==null||finished) return; finished=true;
            new SwingWorker<Void,Void>() { protected Void doInBackground() throws Exception { client.call("POST","ai/jobs/"+job+"/cancel",obj()); return null; } protected void done() { try { get(); message.setText("已停止"); observer.accept(obj("status","cancelled")); } catch(Exception e) { fail(e); } } }.execute();
        }
        void fail(Exception e) { finished=true; if(timer!=null) timer.stop(); Throwable cause=e instanceof ExecutionException?e.getCause():e; message.setText("处理失败"); output.setText(cause.getMessage()); onFailure.accept(cause.getMessage()); }
    }
    private final class ArticleView {
        JsonNode document, article; final String file; final ApiClient client=api;
        final JPanel panel=new JPanel(new BorderLayout()); final JTextPane reading=new JTextPane(); final ToolTabs tabs=new ToolTabs();
        final JCheckBox translation=new JCheckBox("显示译文",true), answers=new JCheckBox("显示答案与解析",false);
        final Map<Integer,String> responses=new HashMap<>(); final JPanel questions=new ScrollColumn(); final List<TaskPanel> tasks=new ArrayList<>();
        final JTextPane definition=new JTextPane(); TaskPanel wordTask; final List<TextSpan> spans=new ArrayList<>(); String selectedWord="",selectedContext=""; int selectedOffset=-1;
        record TextSpan(int start,int end,String context){}
        AudioPlayer audio; JSplitPane split; JPanel readerColumn,wordPanel,questionsPage; JScrollPane phrasesPage; boolean supportOpen=true,maximized; int savedSupportWidth=400; JPanel preferredChat; JLabel selectedQuoteLabel; java.util.function.Consumer<String> quoteIntoChat; long preferredChatRevision;
        ArticleView(JsonNode document) {
            this.document=document; this.article=document.path("article"); file=document.path("file").asText();
            JPanel toolbar=new JPanel(new FlowLayout(FlowLayout.LEFT,8,8)); toolbar.setBackground(PAPER); toolbar.add(translation); toolbar.add(answers);
            button(toolbar,"编辑正文",()->editArticle(client,document));
            button(toolbar,"AI 修改",()->{ String prompt=JOptionPane.showInputDialog(frame,"明确说明需要修改的段落、题目或词条（先预览，再确认保存）"); if(prompt!=null) { var task=new TaskPanel(client,obj("kind","rewrite","file",file,"text",prompt),result->previewGenerated(client,result.path("article"),file,result.path("revision").asLong())); addTask("AI 修改",task); } });
            button(toolbar,"历史版本",()->work(()->client.call("GET","articles/"+ApiClient.encode(file)+"/history",null),rows->{
                if(rows.isEmpty()) { JOptionPane.showMessageDialog(frame,"尚无历史版本"); return; } List<Item> options=new ArrayList<>(); for(var row:rows) options.add(new Item(row.path("data").asText(),"版本 "+row.path("revision").asText()+" · "+row.path("created").asText()));
                Item picked=(Item)JOptionPane.showInputDialog(frame,"选择历史版本预览","文章历史",JOptionPane.PLAIN_MESSAGE,null,options.toArray(),options.getFirst()); if(picked!=null) previewGenerated(client,parse(picked.id()),file,document.path("revision").asLong());
            }));
            button(toolbar,"新建聊天",()->chat(null)); button(toolbar,"历史聊天",this::chatHistory);
            if(article.has("audioUrl")) button(toolbar,"打开音频",()->{ try { var uri=java.net.URI.create(article.path("audioUrl").asText()); require(Set.of("http","https").contains(uri.getScheme()),"相对音频需迁移到可访问的 HTTP(S) 地址"); java.awt.Desktop.getDesktop().browse(uri); } catch(Exception e) { JOptionPane.showMessageDialog(frame,e.getMessage()); } });
            else if(article.path("type").asText().equals("listening")) toolbar.add(new JLabel("此资料无音频"));
            JPanel documentBar=NativeTheme.chrome(new BorderLayout()); documentBar.setPreferredSize(new Dimension(0,42)); documentBar.setBorder(BorderFactory.createEmptyBorder(0,24,0,8));
            String label=catalog.path("library").path("articles").path(file).path("name").asText(article.path("subtitle").asText(article.path("title").asText()));
            JLabel identity=new JLabel(label,new NativeTheme.LineIcon("book"),SwingConstants.LEFT); identity.setIconTextGap(12); identity.setFont(NativeTheme.font(14)); documentBar.add(identity);
            JButton controls=NativeTheme.icon("controls","阅读设置",()->{}); controls.addActionListener(e->{ JPopupMenu menu=new JPopupMenu(); JCheckBoxMenuItem translations=new JCheckBoxMenuItem("显示译文",translation.isSelected()); translations.addActionListener(ev->{ translation.setSelected(translations.isSelected()); renderReading(); renderQuestions(); }); menu.add(translations);
                JCheckBoxMenuItem show=new JCheckBoxMenuItem("显示答案与解析",answers.isSelected()); show.addActionListener(ev->{ answers.setSelected(show.isSelected()); renderQuestions(); }); menu.add(show); menu.addSeparator();
                for(Component component:toolbar.getComponents()) if(component instanceof JButton b) menu(menu,b.getText(),b::doClick); menu.show(controls,0,controls.getHeight()); }); documentBar.add(controls,BorderLayout.EAST);
            readerColumn=new JPanel(new BorderLayout()); readerColumn.add(documentBar,BorderLayout.NORTH);
            JPanel documentActions=new JPanel(new FlowLayout(FlowLayout.RIGHT,2,6)); documentActions.setOpaque(false); documentActions.add(controls); documentActions.add(NativeTheme.icon("plus","新标签页",this::launcher)); documentActions.add(NativeTheme.icon("panelRight","展开 / 收起侧栏",()->{ if(supportOpen) hideSupport(); else showSupport(); })); documentBar.add(documentActions,BorderLayout.EAST);
            tabs.onAdd=this::launcher; tabs.onHide=this::hideSupport; tabs.onMaximize=this::maximizeSupport; tabs.onEmpty=this::hideSupport;
            reading.setEditable(false); reading.setBackground(PAPER); reading.setMargin(new Insets(38,38,70,38)); reading.setForeground(INK);
            JScrollPane reader=new JScrollPane(new ReadingPage(reading)); reader.setBorder(null); readerColumn.add(reader);
            if(article.path("type").asText().equals("listening")){
                JPanel audioSection=new JPanel(new BorderLayout()); audioSection.setBorder(BorderFactory.createEmptyBorder(12,38,0,38)); if(article.has("audioUrl")){ audio=new AudioPlayer(article.path("audioUrl").asText()); audioSection.add(audio); }else{ JLabel missing=new JLabel("本篇没有音频资料"); missing.setFont(NativeTheme.font(12)); missing.setForeground(MUTED); audioSection.add(missing); } JPanel readingBody=new JPanel(new BorderLayout()); readingBody.add(audioSection,BorderLayout.NORTH); readingBody.add(reader); readerColumn.add(readingBody);
            }
            questions.setLayout(new BoxLayout(questions,BoxLayout.Y_AXIS)); questions.setBackground(PAPER); questions.setBorder(BorderFactory.createEmptyBorder(10,22,20,22));
            questionsPage=new JPanel(new BorderLayout()); JPanel questionHeader=new JPanel(new BorderLayout()); questionHeader.setBorder(BorderFactory.createEmptyBorder(20,24,4,24)); questionHeader.add(new JLabel("题目"));
            JButton reveal=NativeTheme.button("显示答案",()->{ answers.setSelected(!answers.isSelected()); renderQuestions(); }); answers.addChangeListener(e->reveal.setText(answers.isSelected()?"隐藏答案":"显示答案")); questionHeader.add(reveal,BorderLayout.EAST); questionsPage.add(questionHeader,BorderLayout.NORTH); questionsPage.add(new JScrollPane(questions));

            definition.setEditable(false); definition.setFont(NativeTheme.font(14)); definition.setMargin(new Insets(20,20,20,20)); definition.setBackground(PAPER);
            wordPanel=new JPanel(new BorderLayout()); wordPanel.add(new JScrollPane(definition)); JPanel wordActions=new JPanel(new FlowLayout(FlowLayout.LEFT));
            button(wordActions,"加入单词本",()->addWord(selectedWord)); button(wordActions,"AI 语境义",()->resolve()); wordPanel.add(wordActions,BorderLayout.SOUTH); 
            JTextArea phrases=new JTextArea(); phrases.setEditable(false); phrases.setLineWrap(true); phrases.setWrapStyleWord(true); phrases.setMargin(new Insets(20,20,20,20)); StringBuilder list=new StringBuilder();
            for(var p:article.path("phrases")) list.append(p.path("en").asText()).append("\n").append(p.path("zh").asText()).append("\n\n"); phrases.setText(list.isEmpty()?"暂无短语":list.toString()); phrasesPage=new JScrollPane(phrases);
            split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,readerColumn,tabs); NativeTheme.split(split); split.setResizeWeight(1); split.setDividerLocation(Math.max(320,frame.getWidth()-44-202-400)); readerColumn.setMinimumSize(new Dimension(0,0)); tabs.setMinimumSize(new Dimension(0,0)); panel.add(split);
            var divider=((javax.swing.plaf.basic.BasicSplitPaneUI)split.getUI()).getDivider(); divider.addMouseListener(new MouseAdapter(){ public void mouseReleased(MouseEvent e){ if(split.getDividerLocation()<100&&!maximized) maximizeSupport(); else if(split.getWidth()-split.getDividerLocation()<90) hideSupport(); else savedSupportWidth=split.getWidth()-split.getDividerLocation(); } });
            hideSupport();
            translation.addActionListener(e->{ renderReading(); renderQuestions(); }); answers.addActionListener(e->renderQuestions()); renderReading(); renderQuestions();
            reading.addMouseListener(new MouseAdapter(){
                @Override public void mouseClicked(MouseEvent e) { if(SwingUtilities.isLeftMouseButton(e) && e.getClickCount()==1 && reading.getSelectedText()==null) SwingUtilities.invokeLater(()->wordAt(e)); }
                @Override public void mousePressed(MouseEvent e){ popup(e); } @Override public void mouseReleased(MouseEvent e){ popup(e); if(SwingUtilities.isLeftMouseButton(e)){ String selected=reading.getSelectedText(); if(selected!=null&&!selected.isBlank()){ JPopupMenu quote=new JPopupMenu(); menu(quote,"在侧边聊天中提问",()->chat(selected)); quote.show(reading,e.getX(),e.getY()+8); } } }
                private void popup(MouseEvent e) {
                    if(!e.isPopupTrigger()) return; String selection=reading.getSelectedText(); JPopupMenu menu=new JPopupMenu();
                    if(selection!=null&&!selection.isBlank()) { menu(menu,"引用选区提问",()->chat(selection)); menu(menu,"加入单词本",()->addWord(selection)); menu(menu,"在新标签查词",()->lookup(selection,true)); }
                    else { WordQuery query=queryAt(e); if(query!=null){ menu(menu,"加入单词本",()->addWord(query.word())); menu(menu,"在新标签页查词",()->lookup(query.word(),true)); } }
                    menu.show(reading,e.getX(),e.getY());
                }
            });
        }
        void dispose() { tasks.forEach(TaskPanel::cancel); if(audio!=null) audio.close(); }
        void update(JsonNode value){ document=value; article=value.path("article"); renderReading(); renderQuestions(); }
        void showSupport(){ supportOpen=true; readerColumn.setVisible(!maximized); split.setRightComponent(tabs); split.setDividerLocation(maximized?0:Math.max(160,(split.getWidth()>0?split.getWidth():frame.getWidth()-44-(sidebar.isVisible()?workspace.getDividerLocation():0))-savedSupportWidth)); panel.revalidate(); }
        void hideSupport(){ supportOpen=false; maximized=false; readerColumn.setVisible(true); split.setRightComponent(null); if(file.equals(current)) applyChrome(this); panel.revalidate(); }
        void maximizeSupport(){ if(maximized){ restoreSupport(); return; } savedSupportWidth=Math.max(300,split.getWidth()-split.getDividerLocation()); maximized=true; supportOpen=true; readerColumn.setVisible(false); split.setRightComponent(tabs); split.setDividerLocation(0); applyChrome(this); }
        void restoreSupport(){ if(!maximized) return; maximized=false; applyChrome(this); showSupport(); }
        void tool(String name){
            Component page=switch(name){ case "题目"->questionsPage; case "单词翻译"->wordPanel; default->phrasesPage; };
            if(tabs.indexOfComponent(page)<0) tabs.addTab(name,new NativeTheme.LineIcon(name.equals("单词翻译")?"book":"list"),page); tabs.setSelectedComponent(page); showSupport();
        }
        void launcher(){
            JPanel page=new JPanel(new BorderLayout()); JPanel content=new JPanel(); content.setLayout(new BoxLayout(content,BoxLayout.Y_AXIS)); content.setBorder(BorderFactory.createEmptyBorder(32,28,32,28)); JLabel title=new JLabel("新标签页"); title.setFont(NativeTheme.font(20).deriveFont(Font.BOLD)); content.add(title); content.add(Box.createVerticalStrut(10)); JLabel hint=new JLabel("选择一个工具，继续阅读。"); hint.setForeground(MUTED); content.add(hint); content.add(Box.createVerticalStrut(24));
            for(String type:List.of("单词翻译","题目","短语","AI 聊天")){ JButton button=NativeTheme.button(type,()->{ tabs.remove(page); if(type.equals("AI 聊天")) chat(null); else tool(type); }); button.setHorizontalAlignment(SwingConstants.LEFT); button.setFont(NativeTheme.font(14)); button.setIcon(new NativeTheme.LineIcon(type.equals("AI 聊天")?"chat":type.equals("单词翻译")?"book":"list")); button.setAlignmentX(Component.LEFT_ALIGNMENT); button.setMaximumSize(new Dimension(Integer.MAX_VALUE,52)); content.add(button); content.add(Box.createVerticalStrut(8)); }
            page.add(content,BorderLayout.NORTH); tabs.addTab("新标签页",new NativeTheme.LineIcon("plus"),page); showSupport();
        }
        void addTask(String title,TaskPanel task){ tasks.add(task); tabs.addTab(title,task.panel); tabs.onClose(task.panel,task::cancel); showSupport(); task.start(); }
        void renderReading() {
            int previous=reading.getCaretPosition(); java.awt.Point scroll=SwingUtilities.getAncestorOfClass(JViewport.class,reading) instanceof JViewport viewport?viewport.getViewPosition():new java.awt.Point(); StyledDocument d=reading.getStyledDocument(); try { d.remove(0,d.getLength()); spans.clear();
                append(d,article.path("title").asText()+"\n",29,true,INK); append(d,article.path("subtitle").asText()+"\n\n",13,false,MUTED);
                if(article.has("instructions")) append(d,article.path("instructions").asText()+"\n\n",15,false,MUTED);
                for(var p:article.path("paragraphs")) {
                    if(p.has("label")) append(d,p.path("label").asText()+"  ",18,true,ACCENT);
                    if(!p.path("en").asText().isBlank()){ int start=d.getLength(); String text=p.path("en").asText(); append(d,text+"\n",18,false,INK); spans.add(new TextSpan(start,start+text.length(),text)); }
                    if((translation.isSelected()||p.path("en").asText().isBlank())&&!p.path("zh").asText().isBlank()) append(d,p.path("zh").asText()+"\n",p.path("en").asText().isBlank()?18:14,false,MUTED);
                    append(d,"\n",17,false,MUTED);
                }
                if(article.has("source")) append(d,"资料来源："+article.path("source").asText(),13,false,MUTED);
reading.setCaretPosition(Math.min(previous,d.getLength())); SwingUtilities.invokeLater(()->{ if(SwingUtilities.getAncestorOfClass(JViewport.class,reading) instanceof JViewport viewport) viewport.setViewPosition(scroll); });
            } catch(BadLocationException e) { throw new IllegalStateException(e); }
        }
        void append(StyledDocument doc,String text,int size,boolean bold,Color color) throws BadLocationException {
            SimpleAttributeSet attrs=new SimpleAttributeSet(); StyleConstants.setFontFamily(attrs,NativeTheme.FONT); StyleConstants.setFontSize(attrs,size); StyleConstants.setBold(attrs,bold); StyleConstants.setForeground(attrs,color); int start=doc.getLength(); doc.insertString(start,text,attrs);
            SimpleAttributeSet spacing=new SimpleAttributeSet(); StyleConstants.setLineSpacing(spacing,size>=25?.15f:size>=18?.55f:.45f); StyleConstants.setSpaceAbove(spacing,size==14?12:size==13?8:0); doc.setParagraphAttributes(start,text.length(),spacing,false);
        }
        JTextArea paragraph(String text){ return new WrappingText(text,14,INK); }
        void renderQuestions() {
            questions.removeAll();
            if(article.path("questions").isEmpty()) questions.add(paragraph("此资料没有题目，可直接精读。"));
            if(!article.path("wordBank").isMissingNode()){ JPanel bank=new JPanel(new GridLayout(0,2,6,6)); bank.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(NativeTheme.LINE),BorderFactory.createEmptyBorder(10,10,10,10))); for(var word:article.path("wordBank")){ JButton b=NativeTheme.button(word.path("key").asText()+"  "+word.path("en").asText(),()->lookup(word.path("en").asText(),false)); b.setHorizontalAlignment(SwingConstants.LEFT); bank.add(b); } bank.setAlignmentX(Component.LEFT_ALIGNMENT); questions.add(bank); questions.add(Box.createVerticalStrut(18)); }
            for(var q:article.path("questions")) {
                int number=q.path("number").asInt();
                JPanel heading=new JPanel(new BorderLayout(9,0)); JLabel n=new JLabel(String.valueOf(number)); n.setFont(NativeTheme.font(12)); n.setForeground(new Color(0xab372f)); n.setVerticalAlignment(SwingConstants.TOP); heading.add(n,BorderLayout.WEST);
                JPanel stem=new JPanel(); stem.setLayout(new BoxLayout(stem,BoxLayout.Y_AXIS)); stem.add(new WrappingText(q.path("en").asText(),14,INK)); if(translation.isSelected()&&!q.path("zh").asText().isBlank()) stem.add(new WrappingText(q.path("zh").asText(),12,MUTED)); heading.add(stem); heading.setAlignmentX(Component.LEFT_ALIGNMENT); questions.add(heading);
                var choices=Article.choices(article,q); String answer=responses.getOrDefault(number,"");
                if(choices.isArray()&&!choices.isEmpty()) {
                    ButtonGroup group=new ButtonGroup();
                    for(var choice:choices) {
                        String key=choice.path("key").asText();
                        JRadioButton radio=new JRadioButton(key){ protected void paintComponent(Graphics graphics){ Graphics2D g=(Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); g.setColor(isSelected()?ACCENT:new Color(0xd9dfd9)); g.drawOval(1,1,21,21); if(isSelected()){ g.setColor(new Color(0xeaf2ed)); g.fillOval(2,2,20,20); } g.setColor(isSelected()?ACCENT:MUTED); g.setFont(NativeTheme.font(10)); FontMetrics metrics=g.getFontMetrics(); g.drawString(key,12-metrics.stringWidth(key)/2,16); g.dispose(); } };
                        radio.setPreferredSize(new Dimension(24,26)); radio.setMinimumSize(new Dimension(24,26)); radio.setSelected(answer.equals(key)); radio.setBorder(null); radio.setContentAreaFilled(false); radio.setToolTipText(key+" · "+choice.path("en").asText()); radio.addActionListener(e->{ responses.put(number,key); if(answers.isSelected()) renderQuestions(); }); group.add(radio);
                        JPanel row=new JPanel(new BorderLayout(9,0)); row.setBorder(BorderFactory.createEmptyBorder(7,21,7,2)); row.setAlignmentX(Component.LEFT_ALIGNMENT); JPanel marker=new JPanel(new BorderLayout()); marker.add(radio,BorderLayout.NORTH); row.add(marker,BorderLayout.WEST); JPanel text=new JPanel(); text.setLayout(new BoxLayout(text,BoxLayout.Y_AXIS)); var english=new WrappingText(choice.path("en").asText(),14,INK); text.add(english); if(translation.isSelected()&&!choice.path("zh").asText().isBlank()) text.add(new WrappingText(choice.path("zh").asText(),12,MUTED)); row.add(text); english.addMouseListener(new MouseAdapter(){ public void mouseClicked(MouseEvent e){ radio.doClick(); } });
                        Color tint=answers.isSelected()&&key.equals(q.path("answer").asText())?new Color(0xeaf2ed):answers.isSelected()&&answer.equals(key)&&!key.equals(q.path("answer").asText())?new Color(0xfbf0ee):PAPER; row.setBackground(tint); text.setBackground(tint); marker.setBackground(tint); for(Component child:text.getComponents()) child.setBackground(tint); questions.add(row);
                    }
                } else {
                    JTextArea input=new JTextArea(answer,3,22); input.setLineWrap(true); input.setWrapStyleWord(true); input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){ public void insertUpdate(javax.swing.event.DocumentEvent e){ update(); } public void removeUpdate(javax.swing.event.DocumentEvent e){ update(); } public void changedUpdate(javax.swing.event.DocumentEvent e){ update(); } void update(){ responses.put(number,input.getText()); } }); questions.add(new JScrollPane(input));
                }
                if(answers.isSelected()) {
                    String qt=q.path("type").asText(article.path("type").asText("choice")); boolean objective=Set.of("reading","listening","choice","cloze","matching").contains(qt);
                    String feedback=objective?(answer.isBlank()?"尚未作答":answer.trim().equalsIgnoreCase(q.path("answer").asText().trim())?"✓ 回答正确":"请对照参考答案"):"参考答案 · 请按要点自查";
                    questions.add(paragraph(feedback+"\n参考答案："+q.path("answer").asText()+"\n解析："+q.path("explanation").asText()+"\n依据："+q.path("evidence").asText()+"\n答案来源："+q.path("answerSource").asText("unknown")));
                }
                questions.add(Box.createVerticalStrut(24));
            }
            questions.revalidate(); questions.repaint();
        }
        record WordQuery(String word,String context,int offset){}
        WordQuery queryAt(MouseEvent e){ int offset=reading.viewToModel2D(e.getPoint()); Matcher matcher=Pattern.compile("[A-Za-z]+(?:['’–-][A-Za-z]+)*").matcher(reading.getText()); while(matcher.find()) if(offset>=matcher.start()&&offset<matcher.end()){ for(var span:spans) if(matcher.start()>=span.start()&&matcher.start()<span.end()) return new WordQuery(matcher.group(),span.context(),matcher.start()-span.start()); return new WordQuery(matcher.group(),"",-1); } return null; }
        void wordAt(MouseEvent e){ WordQuery query=queryAt(e); if(query!=null){ lookup(query.word(),false); selectedContext=query.context(); selectedOffset=query.offset(); } }
        void lookup(String raw,boolean newTab) {
            String w; try { w=word(raw); } catch(Fault e) { return; }
            if(!newTab){ if(wordTask!=null) wordTask.cancel(); selectedWord=w; selectedContext=""; selectedOffset=-1; for(var p:article.path("paragraphs")) if(p.path("en").asText().toLowerCase(Locale.ROOT).contains(w.toLowerCase(Locale.ROOT))) { selectedContext=p.path("en").asText(); break; } }
            if(!newTab) { definition.setText(w+"\n正在查询词库…"); tool("单词翻译"); }
            String query=w; work(()->client.call("GET","lexicon?word="+ApiClient.encode(query),null),value->{
                StringBuilder text=new StringBuilder(query+"\n\n"); var entry=value.path("entry");
                JsonNode manual=article.path("dictionary").path(query); if(!manual.isMissingNode()) text.append("本篇释义\n").append(manual.path("meaning").asText()).append("\n语境：").append(manual.path("context").asText()).append("\n\n");
                if(entry.isNull()) text.append("词库暂无此词，可使用 AI 语境义。\n");
                else {
                    text.append(entry.path("headword").asText()).append("\n英 / ").append(entry.path("phonetics").path("uk").asText()).append(" /   美 / ").append(entry.path("phonetics").path("us").asText()).append(" /\n\n");
                    for(var sense:entry.path("senses")) { text.append(sense.path("pos").asText()).append("  "); for(var t:sense.path("translations")) text.append(t.asText()).append("；"); text.append("\n"); }
                    text.append("\n搭配\n"); for(var p:entry.path("collocations")) text.append(p.path("text").asText()).append("  ").append(p.path("meaning").asText()).append("\n");
                    text.append("\n词根词缀\n"); for(var m:entry.path("morphemes")) text.append(m.path("form").asText()).append("  ").append(m.path("meaning").asText()).append("\n");
                    text.append("\n来源：").append(value.path("source").asText());
                }
                if(newTab){ JPanel page=new JPanel(new BorderLayout()); JTextArea fresh=paragraph(text.toString()); fresh.setMargin(new Insets(24,24,24,24)); page.add(new JScrollPane(fresh)); JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT)); button(actions,"加入单词本",()->addWord(query)); page.add(actions,BorderLayout.SOUTH); tabs.addTab(query,page); showSupport(); }
                else if(selectedWord.equals(query)){ definition.setText(text.toString()); styleDefinition(); definition.setCaretPosition(0); if(modelBox.getSelectedItem()!=null&&!selectedContext.isBlank()) resolve(); }
            });
        }
        void addWord(String w) { if(w==null||w.isBlank()) return; work(()->client.call("POST","vocabulary/add",obj("file",file,"word",w)),v->status.setText("已加入单词本："+w)); }
        void styleDefinition(){ var doc=definition.getStyledDocument(); SimpleAttributeSet body=new SimpleAttributeSet(); StyleConstants.setFontFamily(body,NativeTheme.FONT); StyleConstants.setFontSize(body,14); doc.setCharacterAttributes(0,doc.getLength(),body,false); int end=definition.getText().indexOf('\n'); SimpleAttributeSet heading=new SimpleAttributeSet(); StyleConstants.setFontSize(heading,27); if(end>0) doc.setCharacterAttributes(0,end,heading,false); }
        void resolve(){
            if(selectedWord.isBlank()||selectedContext.isBlank()) return; if(wordTask!=null&&!wordTask.finished) wordTask.cancel(); String w=selectedWord,context=selectedContext; int offset=selectedOffset;
            wordTask=new TaskPanel(client,obj("kind","resolve","file",file,"word",w,"context",context,"offset",offset),result->{ if(selectedWord.equals(w)&&selectedContext.equals(context)&&selectedOffset==offset){ definition.setText(definition.getText()+"\n\n语境义："+result.path("translation").asText()); styleDefinition(); } }); tasks.add(wordTask); wordTask.onFailure=message->{ if(selectedWord.equals(w)) status.setText("语境义暂不可用："+message); }; wordTask.start();
        }
        void chat(String quote) {
            if(quote!=null&&preferredChat!=null&&quoteIntoChat!=null&&preferredChatRevision==document.path("revision").asLong()&&tabs.indexOfComponent(preferredChat)>=0){ quoteIntoChat.accept(quote); tabs.setSelectedComponent(preferredChat); showSupport(); return; }
            work(()->client.call("POST","chats",obj("file",file,"revision",document.path("revision"))),session->{
                showChat(session,quote);
            });
        }
        void chatHistory() {
            work(()->client.call("GET","chats",null),rows->{ List<Item> choices=new ArrayList<>(); for(var row:rows) if(row.path("data").path("file").asText().equals(file)) choices.add(new Item(row.path("id").asText(),row.path("updated").asText()+" · "+row.path("data").path("messages").size()+" 条消息"));
                if(choices.isEmpty()){ JOptionPane.showMessageDialog(frame,"此文章暂无聊天记录"); return; } Item choice=(Item)JOptionPane.showInputDialog(frame,"选择历史聊天","历史聊天",JOptionPane.PLAIN_MESSAGE,null,choices.toArray(),choices.getLast());
                if(choice!=null) work(()->client.call("GET","chats/"+choice.id(),null),session->showChat(session,null)); });
        }
        void showChat(JsonNode session,String quote){
            String sessionId=session.path("id").asText(); JPanel chat=new JPanel(new BorderLayout()); Transcript conversation=new Transcript();
            for(var message:session.path("data").path("messages")){ conversation.message(message.path("role").asText(),message.path("content").asText(),null); conversation.sources(message.path("sources")); }
            JLabel context=new JLabel(session.path("data").path("snapshot").path("subtitle").asText(article.path("title").asText())+" · 文章上下文已关联"); context.setFont(NativeTheme.font(11)); context.setForeground(MUTED); context.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,1,1,0,NativeTheme.LINE),BorderFactory.createEmptyBorder(15,16,15,16))); chat.add(context,BorderLayout.NORTH);
            chat.add(new JScrollPane(conversation));
            JPanel bottom=new JPanel(new BorderLayout(0,10)); bottom.setBorder(BorderFactory.createEmptyBorder(10,14,14,14));
            JPanel shortcuts=new JPanel(new GridLayout(2,2,6,6)); JTextArea input=new JTextArea(4,24); input.setLineWrap(true); input.setWrapStyleWord(true); input.setMargin(new Insets(10,10,8,10)); input.setToolTipText("结合文章提问…");
            for(String prompt:List.of("分析句子语法","解释句子作用","翻译并解释表达","梳理文章思路")) shortcuts.add(NativeTheme.button(prompt,()->input.setText(prompt)));
            bottom.add(shortcuts,BorderLayout.NORTH); JPanel compose=new JPanel(new BorderLayout(0,3)); compose.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xc8d5ce)),BorderFactory.createEmptyBorder(8,8,6,8)));
            final String[] currentQuote={quote}; JLabel quoteText=new JLabel(); JPanel quoteRow=new JPanel(new BorderLayout()); quoteText.setFont(NativeTheme.font(11)); quoteText.setForeground(MUTED); quoteRow.add(quoteText);
            Runnable updateQuote=()->{ quoteText.setText(currentQuote[0]==null?"":currentQuote[0].replace('\n',' ')); quoteText.setToolTipText(currentQuote[0]); quoteRow.setVisible(currentQuote[0]!=null); }; quoteRow.add(NativeTheme.icon("close","移除引用",()->{ currentQuote[0]=null; updateQuote.run(); }),BorderLayout.EAST); updateQuote.run(); compose.add(quoteRow,BorderLayout.NORTH); compose.add(new JScrollPane(input));
            JPanel actions=new JPanel(new BorderLayout()); JComboBox<String> picker=new JComboBox<>(); for(int i=0;i<modelBox.getItemCount();i++) picker.addItem(modelBox.getItemAt(i)); picker.setSelectedItem(modelBox.getSelectedItem()); picker.setFont(NativeTheme.font(10)); picker.setPreferredSize(new Dimension(180,26)); actions.add(picker,BorderLayout.WEST);
            JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,1,0)); JLabel error=new JLabel(" "); error.setFont(NativeTheme.font(11)); error.setForeground(new Color(0xab372f));
            JCheckBox webSearch=new JCheckBox("联网搜索"); webSearch.setFont(NativeTheme.font(11)); webSearch.setToolTipText("开启后调用 DeepSeek 官方搜索接口，可能产生费用。"); Runnable searchAvailability=()->{ var choice=picker.getSelectedItem()==null?null:modelSelections.get(picker.getSelectedItem().toString()); boolean available=choice!=null&&choice.path("searchSupported").asBoolean(); webSearch.setVisible(available); if(!available) webSearch.setSelected(false); }; picker.addActionListener(e->searchAvailability.run()); searchAvailability.run(); buttons.add(webSearch);
            final TaskPanel[] active=new TaskPanel[1]; final boolean[] composing={false};
            java.util.function.BiConsumer<JsonNode,Boolean> submit=(request,retry)->{
                if(active[0]!=null&&!active[0].finished) return;
                TaskPanel task=new TaskPanel(client,request,result->{ conversation.message("assistant",result.path("text").asText(),null); conversation.sources(result.path("sources")); error.setText(" "); if(result.has("articleEdit")){ preferredChatRevision=result.path("articleEdit").path("revision").asLong(); work(()->client.call("GET","articles/"+ApiClient.encode(file),null),saved->{ var view=views.get(file); if(view!=null) view.update(saved); refresh(); }); } }); active[0]=task; tasks.add(task);
                String originalText=request.path("text").asText(),originalQuote=request.path("quote").asText(null);
                task.onStarted=()->{ error.setText(" "); if(!retry){ conversation.message("user",originalText,originalQuote); input.setText(""); currentQuote[0]=null; updateQuote.run(); } };
                task.observer=value->{ String state=value.path("status").asText(); if(value.path("searchStatus").asText().equals("searching")) error.setText("正在联网搜索…"); if(value.has("output")) conversation.streaming(value.path("output").asText()); if(state.equals("failed")){ error.setText(value.path("error").asText()); conversation.streaming(""); } if(state.equals("cancelled")) { error.setText("已停止，可重新回答"); conversation.stopStreaming(); } };
                task.onFailure=error::setText; task.start();
            };
            JButton send=NativeTheme.button("↑",()->{ if(input.getText().isBlank()||composing[0]) return; var request=obj("kind","chat","sessionId",sessionId,"text",input.getText()); if(currentQuote[0]!=null) request.put("quote",currentQuote[0]); if(webSearch.isVisible()&&webSearch.isSelected()) request.put("webSearch",true); if(picker.getSelectedItem()!=null){ JsonNode choice=modelSelections.get(picker.getSelectedItem().toString()); if(choice!=null){ request.put("model",choice.path("model").asText()); request.put("profileId",choice.path("profileId").asText()); } } submit.accept(request,false); }); send.setToolTipText("发送（Enter）"); send.setForeground(ACCENT);
            buttons.add(NativeTheme.icon("retry","重新回答",()->{ if(active[0]!=null&&active[0].finished){ var request=(ObjectNode)active[0].request.deepCopy(); request.put("retry",active[0].completed); submit.accept(request,true); } })); buttons.add(NativeTheme.icon("stop","停止",()->{ if(active[0]!=null) active[0].cancel(); })); buttons.add(send); actions.add(buttons,BorderLayout.EAST); compose.add(actions,BorderLayout.SOUTH);
            JPanel inputSection=new JPanel(new BorderLayout(0,5)); inputSection.add(error,BorderLayout.NORTH); inputSection.add(compose); bottom.add(inputSection); chat.add(bottom,BorderLayout.SOUTH);
            input.addInputMethodListener(new InputMethodListener(){ public void caretPositionChanged(java.awt.event.InputMethodEvent e){} public void inputMethodTextChanged(java.awt.event.InputMethodEvent e){ var text=e.getText(); composing[0]=text!=null&&text.getEndIndex()>e.getCommittedCharacterCount(); } });
            input.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"send"); input.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"),"insert-break"); input.getActionMap().put("send",new AbstractAction(){ public void actionPerformed(ActionEvent e){ if(!composing[0]) send.doClick(); } });
            tabs.addTab("AI · "+article.path("subtitle").asText("聊天"),new NativeTheme.LineIcon("chat"),chat); tabs.onClose(chat,()->{ if(active[0]!=null) active[0].cancel(); if(preferredChat==chat){ preferredChat=null; quoteIntoChat=null; } });
            preferredChat=chat; preferredChatRevision=session.path("data").path("articleRevision").asLong(); quoteIntoChat=value->{ currentQuote[0]=value; updateQuote.run(); input.requestFocusInWindow(); }; showSupport(); input.requestFocusInWindow();
        }
    }
}
