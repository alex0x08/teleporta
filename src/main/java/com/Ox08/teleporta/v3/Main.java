package com.Ox08.teleporta.v3;
import java.util.ArrayList;
import java.util.List;
import static com.Ox08.teleporta.v3.TeleportaCommons.setDebugLogging;
/**
 * Start class for both client and server sides.
 * Client side is called 'Portal' and server - 'Relay'
 * @author 0x08
 * @since 1.0
 */
public class Main {
    public static void main(String[] args) throws Exception {
        // load build info
        final SystemInfo si = new SystemInfo();
        si.load();
        // if no arguments provided - just print help
        if (args.length == 0) {
            printHelp();
            return;
        }
        // cleaned arguments, without key-value parameters
        final List<String> cleaned = new ArrayList<>();
        for (String a : args) {
            // dirty hack to accept env params *after* -jar argument passed
            if (a.startsWith("-D") && a.length() > 2 && a.contains("=")) {
                final String[] p = a.substring(2).split("=");
                System.setProperty(p[0], p[1]);
                continue;
            }
            cleaned.add(a);
        }
        // if there are no required params provided - print help and exit
        if (cleaned.isEmpty()) {
            printHelp();
            return;
        }
        // check if debug messages enabled
        final boolean debugMessages = Boolean
                .parseBoolean(System.getProperty("appDebug", "false"));
        // adjust logging
        if (debugMessages) {
            setDebugLogging();
        }
        // get relay URL
        String relayUrl = cleaned.get(0);
        if (relayUrl.isEmpty()) {
            printHelp();
            return;
        }
        relayUrl = relayUrl.toLowerCase();

        final boolean relay;

        // if there is '-relay' parameter - start Teleporta in 'relay' mode
        if ("-relay".equalsIgnoreCase(relayUrl)) {
            relay = true;
        // if there is an argument that starts with http or https - use it as relay url
        // and start Teleporta in 'portal' mode
        } else if (relayUrl.startsWith("http") || relayUrl.startsWith("https")) {
            relay = false;
         // otherwise just prints help and exit
        } else {
            printHelp();
            return;
        }
        // do we need to show logo?
        final boolean showLogo = Boolean.parseBoolean(System.getProperty("showLogo", "true"));
        if (showLogo) {
            printLogo(relay,si);
        }
        if (relay) {
            TeleportaRelay.init();
        } else {
            final boolean enableClipboard =
                    Boolean.parseBoolean(System.getProperty("clipboard", "false"));
            TeleportaClient.init(relayUrl, enableClipboard);
        }
    }
    static void printHelp() {
        System.out.println("Use as:");
        System.out.println("http://relay.url:port/seed (copy full url from relay output");
        System.out.println("-relay  Will start Teleporta Relay");
    }
    private static final String TELE_LOGO
            = "⣿⣿⣿⣿⣿⣿⣿⢟⠫⠓⠚⠉⠙⠓⠫⢻⠿⣟⠿⠭⠩⠛⡻⣿⣿⣿⣿⣿⣿⣿ \n"
            + "⣿⣿⣿⣿⣿⢟⠕⠁⣠⠴⠒⠋⠉⢉⣉⣛⣛⣲⣤⣀⠔⠒⠛⢒⣋⣹⣛⣿⣿⣿ Teleporta %s v%s \n"
            + "⣿⣿⣿⣿⣏⠎⠀⠀⠀⠀⠤⣖⡫⠝⠒⠂⠀⠀⠐⠺⣷⡲⠭⠛⠓⠒⠚⠫⠬⡻ Build: %s, created: %s\n"
            + "⣿⣿⡿⢟⠝⠀⠀⠀⠀⠮⣉⠀⠀⠀⠀⣴⡿⠿⡆⠀⠈⡇⠀⠀⢰⣿⠿⡆⠀⠈ \n"
            + "⣿⠏⠀⠀⠀⠀⠀⠀⠀⠀⠠⡑⢄⠀⠀⠻⠿⠶⠃⠀⢀⡧⢄⡀⠘⠻⠶⠁⠀⣀ \n"
            + "⢧⠃⠀⠀⠀⠀⠀⠀⠀⠀⣄⡈⠓⣯⣖⣲⠤⠤⠴⠶⠯⠽⠦⢾⣿⡭⣬⡤⣩⣾ \n"
            + "⠂⠀⠀⠀⠀⠀⠀⢀⡤⠴⠒⠉⠉⣁⡠⠤⠤⠔⠒⠒⠶⠶⠦⠤⢤⣈⣉⠛⢼⣿ \n"
            + "⠀⠀⠀⠀⠀⠀⢰⠁⠠⠴⠒⢋⣉⣠⠤⠴⠖⠒⠚⠛⠛⠛⠛⠛⠒⠲⠮⣭⡓⠯ \n"
            + "⠀⠀⠀⠀⠀⠘⢎⠳⠤⠒⠋⠉⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢙⣳ \n"
            + "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠠⣱⣿ \n"
            + "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠠⣱⣿⣿ \n"
            + "⣆⡀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⢀⣴⣿⣿⣿ \n"
            + "⠈⠙⠵⣒⠤⠤⣀⣀⣀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⡀⠀⣔⣮⣶⣿⣿⣿⣿⣿ \n"
            + "⠀⠀⠀⠀⠉⠉⠒⠒⠮⠭⠭⢉⣉⣈⡉⠉⠭⠭⠝⠋⠘⠝⠻⣿⣿⣿⣿⣿⣿⣿ \n"
            + "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣮⡻⣿⣿⣿⣿⣿ \n";
    static void printLogo(boolean relay,SystemInfo si) {
        System.out.printf(TELE_LOGO,
                relay? "Relay" : "Portal",
                si.getBuildVersion(), si.getBuildNum(), si.getBuildTime());
    }
}
