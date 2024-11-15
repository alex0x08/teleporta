package com.Ox08.teleporta.v3;
import com.Ox08.teleporta.v3.messages.TeleportaError;
import com.Ox08.teleporta.v3.messages.TeleportaSysMessage;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import static com.Ox08.teleporta.v3.TeleportaCommons.setDebugLogging;
/**
 * Start class for both client and server sides.
 * Client side is called 'Portal' and server - 'Relay'
 * @author 0x08
 * @since 1.0
 */
public class Main {
    public static void main(String[] args) throws Exception {

        // try to load config file first (if exist)
        final File configFile = new File("teleporta.properties");
        boolean configLoaded = false;
        if (configFile.exists() && configFile.isFile() && configFile.canRead()) {
            final Properties props = new Properties();
            try (FileInputStream in = new FileInputStream(configFile)) {
                props.load(in);
                // note: don't use System.setProperties(props) here !
                // it will destroy (by overwrite) whole env
                for (Object k:props.keySet()) {
                    final Object v = props.get(k);
                    // set only non-defined properties
                    if (System.getProperty(k.toString(),null) == null) {
                        System.setProperty(k.toString(),v.toString());
                    }
                }
                configLoaded = true;
            }
        }

        // if no arguments provided and no config found - just start default relay
        if (args.length == 0 && !configLoaded) {
            //printHelp();
            startDefaultRelay();
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
        // check for user specified locale
        setupLocale();

        // check if debug messages enabled
        final boolean debugMessages = Boolean
                .parseBoolean(System.getProperty("appDebug", "false"));
        // adjust logging
        if (debugMessages) {
            setDebugLogging();
        }

        // if there are no required params provided - start default relay and exit
        if (cleaned.isEmpty() && ! configLoaded) {
            //printHelp();
            startDefaultRelay();
            return;
        }

        // get relay URL
        String relayUrl = cleaned.isEmpty() ?
                System.getProperty("relayUrl",null) : cleaned.get(0);
        if (relayUrl == null || relayUrl.isEmpty()) {
           // now we start default relay instead of printing help
            startDefaultRelay();
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

        // load build info, its done so lately because contains localized error messages now,
        // so must be called after locale is set
        SystemInfo.SI.load();

        // do we need to show logo?
        final boolean showLogo = Boolean.parseBoolean(
                System.getProperty("showLogo", "true"));
        if (showLogo) {
            printLogo(relay);
        }
        // clipboard needs to be enabled both on relay and portal sides
        final boolean enableClipboard =
                Boolean.parseBoolean(System.getProperty("clipboard", "false"));
        final boolean clearOutgoing =
                Boolean.parseBoolean(System.getProperty("clearOutgoing", "false"));
        final boolean relayHasPortal =
                Boolean.parseBoolean(System.getProperty("relayHasPortal", "false"));
        if (relay) {
            TeleportaRelay.init(enableClipboard,clearOutgoing,relayHasPortal);
        } else {
            TeleportaClient.init(relayUrl, enableClipboard,clearOutgoing);
        }
    }

    static void startDefaultRelay() throws Exception {
        // load build info
        SystemInfo.SI.load();
        TeleportaRelay.init(false,false,true);
    }
    /**
     * We need to make programmatic locale toggle, because standard way -Duser.country -Duser.language=
     * works only if these arguments passed *before* -jar, not after
     */
    static void setupLocale() {
        String lang = System.getProperty("lang",null);
        // ignore completely if switch is not set
        if (lang==null || lang.isEmpty()) {
            return;
        }
        lang = lang.toLowerCase();
        // we support only Russian and English for now
        final Locale locale = lang.equals("ru") ? Locale.forLanguageTag("ru-RU") : Locale.US;
        // set default locale
        Locale.setDefault(locale);
        // set locale for messages
        TeleportaSysMessage.instance().setErrorLocale(locale);
        // and for errors
        TeleportaError.instance().setErrorLocale(locale);
    }

    static void printHelp() throws IOException {
        System.out.println("Use as:");
        System.out.println("http://relay.url:port/seed (copy full url from relay output");
        System.out.println("-relay  Will start Teleporta Relay");
        System.out.println("Press any key to exit.");
        if (System.in.read()>0) {
            System.exit(0);
        }
    }
    private  static final String TELE_LOGO =
            ".-------.\n" +
            "| T.--. | Teleporta %s v%s\n" +
            "| :/ \\: | Build: %s, created: %s\n" +
            "| (___) |\n" +
            "| '--'T | Created by Alex Chernyshev, @alex0x08 \n" +
            "`------'  (c) 0x08 Software, 2015-2024 https://0x08.ru \n";

    static void printLogo(boolean relay) {
        final SystemInfo si = SystemInfo.SI;
        System.out.printf(TELE_LOGO,
                relay  ?TeleportaSysMessage.of("teleporta.system.message.teleportaRelayMode"):
                        TeleportaSysMessage.of("teleporta.system.message.teleportaPortalMode"),
                si.getBuildVersion(), si.getBuildNum(), si.getBuildTime());
    }

}
