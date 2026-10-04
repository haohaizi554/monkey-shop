import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一键拉起本机全部服务：MySQL、Redis、Vault、SeaweedFS、ClamAV、
 * 可观测性栈、Spring Boot 和 Vite。
 *
 * <p>在仓库根目录执行：{@code java StartAll.java}
 */
public final class StartAll {
    private StartAll() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.help()) {
            System.out.println(options.usage());
            return;
        }

        Path repoRoot = findRepoRoot();
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            throw new IllegalStateException("LOCALAPPDATA 未设置，无法定位本机运行目录");
        }
        Path runtimeRoot = Path.of(localAppData, "MonkeyShop");
        Path scripts = repoRoot.resolve("scripts");
        String proxyUri = options.proxyUri();

        Map<String, String> childEnv = Map.of();
        Path supportVersions = runtimeRoot.resolve(Path.of("support", "tools", "versions.json"));
        if (Files.notExists(supportVersions)) {
            String httpsProxy = System.getenv("HTTPS_PROXY");
            if (httpsProxy == null || httpsProxy.isBlank()) {
                childEnv = Map.of("HTTPS_PROXY", proxyUri);
            }
            System.out.println("==> Production-support tools are missing; bootstrapping once through " + proxyUri);
        }

        Path observabilityVersions = runtimeRoot.resolve(Path.of("tools", "observability", "versions.json"));
        if (Files.notExists(observabilityVersions)) {
            System.out.println("==> Observability tools are missing; bootstrapping once through " + proxyUri);
            run(repoRoot, childEnv, List.of(
                    "powershell.exe",
                    "-NoProfile",
                    "-ExecutionPolicy", "Bypass",
                    "-File", scripts.resolve("bootstrap-local-observability.ps1").toString(),
                    "-ProxyUri", proxyUri
            ));
        }

        System.out.println("==> Starting MySQL, Redis, Vault, SeaweedFS, ClamAV, observability, backend, and frontend");
        List<String> command = new ArrayList<>();
        command.add("powershell.exe");
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(scripts.resolve("start-local.ps1").toString());
        addIfPresent(command, "-EnvPath", options.envPath());
        addIfPresent(command, "-LocalEnvPath", options.localEnvPath());
        addIfPresent(command, "-MemuraiExecutable", options.memuraiExecutable());
        command.add("-MySqlPort");
        command.add(Integer.toString(options.mysqlPort()));
        command.add("-MySqlXPort");
        command.add(Integer.toString(options.mysqlXPort()));
        command.add("-RedisPort");
        command.add(Integer.toString(options.redisPort()));
        command.add("-BackendPort");
        command.add(Integer.toString(options.backendPort()));
        command.add("-FrontendPort");
        command.add(Integer.toString(options.frontendPort()));
        command.add("-StartupTimeoutSeconds");
        command.add(Integer.toString(options.startupTimeoutSeconds()));
        command.add("-WithProductionSupport");
        command.add("-WithObservability");
        run(repoRoot, childEnv, command);

        System.out.println("Prometheus: http://127.0.0.1:9090");
        System.out.println("Grafana:    http://127.0.0.1:3000");
        System.out.println("Loki:       http://127.0.0.1:3100");
        System.out.println("Tempo:      http://127.0.0.1:3200");
    }

    private static Path findRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve(Path.of("scripts", "start-local.ps1")))
                    && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("请在 MonkeyShop 仓库根目录运行: java StartAll.java");
    }

    private static void addIfPresent(List<String> command, String name, String value) {
        if (value != null && !value.isBlank()) {
            command.add(name);
            command.add(value);
        }
    }

    private static void run(Path directory, Map<String, String> extraEnv, List<String> command)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.inheritIO();
        builder.environment().putAll(extraEnv);
        int code = builder.start().waitFor();
        if (code != 0) {
            System.exit(code);
        }
    }

    private record Options(
            String envPath,
            String localEnvPath,
            String memuraiExecutable,
            String proxyUri,
            int mysqlPort,
            int mysqlXPort,
            int redisPort,
            int backendPort,
            int frontendPort,
            int startupTimeoutSeconds,
            boolean help
    ) {
        private static Options parse(String[] args) {
            String envPath = "";
            String localEnvPath = "";
            String memuraiExecutable = "";
            String proxyUri = firstNonBlank(System.getenv("HTTPS_PROXY"), "http://127.0.0.1:7890");
            int mysqlPort = 3306;
            int mysqlXPort = 33060;
            int redisPort = 6379;
            int backendPort = 8888;
            int frontendPort = 5173;
            int startupTimeoutSeconds = 600;
            boolean help = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> help = true;
                    case "--env" -> envPath = next(args, ++i, arg);
                    case "--local-env" -> localEnvPath = next(args, ++i, arg);
                    case "--memurai" -> memuraiExecutable = next(args, ++i, arg);
                    case "--proxy" -> proxyUri = next(args, ++i, arg);
                    case "--mysql-port" -> mysqlPort = port(next(args, ++i, arg), arg);
                    case "--mysql-x-port" -> mysqlXPort = port(next(args, ++i, arg), arg);
                    case "--redis-port" -> redisPort = port(next(args, ++i, arg), arg);
                    case "--backend-port" -> backendPort = port(next(args, ++i, arg), arg);
                    case "--frontend-port" -> frontendPort = port(next(args, ++i, arg), arg);
                    case "--timeout" -> startupTimeoutSeconds = positive(next(args, ++i, arg), arg);
                    default -> throw new IllegalArgumentException("无法识别的参数: " + arg + "\n" + usage());
                }
            }
            return new Options(
                    envPath,
                    localEnvPath,
                    memuraiExecutable,
                    proxyUri,
                    mysqlPort,
                    mysqlXPort,
                    redisPort,
                    backendPort,
                    frontendPort,
                    startupTimeoutSeconds,
                    help
            );
        }

        private static String usage() {
            return """
                    用法: java StartAll.java [选项]

                      --env <path>            仓库 .env 路径
                      --local-env <path>      本机 runtime env 路径
                      --memurai <path>        Memurai 可执行文件
                      --proxy <uri>           首次下载工具时使用的代理，默认 http://127.0.0.1:7890
                      --mysql-port <port>     默认 3306
                      --mysql-x-port <port>   默认 33060
                      --redis-port <port>     默认 6379
                      --backend-port <port>   默认 8888
                      --frontend-port <port>  默认 5173
                      --timeout <seconds>     默认 600
                      --help                  显示此说明
                    """;
        }

        private static String next(String[] args, int index, String name) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException(name + " 缺少取值");
            }
            return args[index];
        }

        private static int port(String value, String name) {
            int parsed = positive(value, name);
            if (parsed > 65535) {
                throw new IllegalArgumentException(name + " 超出端口范围: " + value);
            }
            return parsed;
        }

        private static int positive(String value, String name) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed <= 0) {
                    throw new NumberFormatException(value);
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(name + " 需要正整数: " + value);
            }
        }

        private static String firstNonBlank(String preferred, String fallback) {
            if (preferred != null && !preferred.isBlank()) {
                return preferred;
            }
            return fallback;
        }
    }
}
