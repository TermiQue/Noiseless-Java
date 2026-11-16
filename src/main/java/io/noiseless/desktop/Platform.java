package io.noiseless.desktop;

final class Platform {
    private Platform() {}
    static String menuShortcut(String key) { return menuShortcut(System.getProperty("os.name"),key); }
    static String menuShortcut(String os,String key) { return (os.startsWith("Mac")?"meta ":"ctrl ")+key; }
}
