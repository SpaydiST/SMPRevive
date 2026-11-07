package macronux.smprevive;

import java.nio.file.Files;
import java.nio.file.Path;

public final class BuildContractChecks {
    private BuildContractChecks() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path pom = root.resolve("pom.xml");
        Path wrapperProperties = root.resolve(".mvn/wrapper/maven-wrapper.properties");
        Path wrapperCmd = root.resolve("mvnw.cmd");
        Path wrapperUnix = root.resolve("mvnw");
        Path buildCmd = root.resolve("build.cmd");
        Path gitignore = root.resolve(".gitignore");

        require(Files.isRegularFile(pom), "pom.xml is missing");
        require(Files.isRegularFile(wrapperProperties)
                        && Files.isRegularFile(wrapperCmd)
                        && Files.isRegularFile(wrapperUnix),
                "Maven wrapper files are missing");
        require(Files.isRegularFile(buildCmd), "build.cmd is missing");
        require(Files.isRegularFile(gitignore), ".gitignore is missing");

        String pomText = Files.readString(pom);
        String wrapperText = Files.readString(wrapperProperties);
        String wrapperCmdText = Files.readString(wrapperCmd);
        String wrapperUnixText = Files.readString(wrapperUnix);
        String buildText = Files.readString(buildCmd);
        String ignoreText = Files.readString(gitignore);
        String paperDescriptor = Files.readString(root.resolve("src/main/resources/paper-plugin.yml"));
        String bukkitDescriptor = Files.readString(root.resolve("src/main/resources/plugin.yml"));
        String advancementPack = Files.readString(root.resolve("src/main/resources/smprevive_advancements/pack.mcmeta"));
        String resourcePack = Files.readString(root.resolve("resourcepack/pack.mcmeta"));

        require(pomText.contains("<groupId>macronux</groupId>")
                        && pomText.contains("<artifactId>SMPRevive</artifactId>")
                        && pomText.contains("<version>1.3.7</version>")
                        && pomText.contains("<java.version>25</java.version>")
                        && pomText.contains("<paper.version>26.3.build.134-beta</paper.version>")
                        && pomText.contains("<maven.compiler.release>")
                        && pomText.contains("<release>")
                        && pomText.contains("maven.compiler.release")
                        && pomText.contains("<version>")
                        && pomText.contains("<version>" + "$" + "{paper.version}</version>")
                        && pomText.contains("<artifactId>paper-api</artifactId>")
                        && pomText.contains("<scope>provided</scope>")
                        && pomText.contains("<filtering>true</filtering>")
                        && pomText.contains("<include>plugin.yml</include>")
                        && pomText.contains("<include>paper-plugin.yml</include>")
                        && !pomText.contains("maven-shade-plugin")
                        && pomText.contains("<directory>src/main/resources</directory>"),
                "POM must preserve coordinates, Java 25, Paper 26.3 build 134 beta, descriptor-only filtering, and no shading");
        String projectVersion = "$" + "{project.version}";
        require(paperDescriptor.contains("version: '" + projectVersion + "'")
                        && bukkitDescriptor.contains("version: " + projectVersion),
                "plugin descriptors must take the version from the POM");
        require(paperDescriptor.contains("api-version: '26.3'")
                        && bukkitDescriptor.contains("api-version: '26.3'")
                        && bukkitDescriptor.contains("Minecraft 26.3"),
                "plugin descriptors must advertise Minecraft/Paper 26.3");
        // Compare without whitespace so pretty-printed and inline JSON are both accepted.
        String compactAdvancementPack = advancementPack.replaceAll("\\s+", "");
        String compactResourcePack = resourcePack.replaceAll("\\s+", "");
        require(compactAdvancementPack.contains("\"min_format\":[121,0]")
                        && compactAdvancementPack.contains("\"max_format\":[121,0]")
                        && compactResourcePack.contains("\"min_format\":[97,1]")
                        && compactResourcePack.contains("\"max_format\":[97,1]"),
                "bundled packs must declare Minecraft 26.3 pack versions");
        require(wrapperText.contains("wrapperVersion=3.3.4")
                        && wrapperText.contains("distributionType=bin")
                        && wrapperText.contains("apache-maven/3.9.16/apache-maven-3.9.16-bin.zip"),
                "wrapper must pin Maven Wrapper 3.3.4 and Maven 3.9.16");
        require(wrapperCmdText.contains("JAVA_HOME")
                        && wrapperCmdText.contains(".jdks")
                        && wrapperCmdText.contains("jdk25")
                        && wrapperCmdText.contains("MAVEN_USER_HOME"),
                "Windows wrapper must discover host JDK 25");
        require(wrapperUnixText.contains("JAVA_HOME")
                        && wrapperUnixText.contains(".jdks")
                        && wrapperUnixText.contains("jdk25")
                        && wrapperUnixText.contains("MAVEN_USER_HOME"),
                "POSIX wrapper must discover JDK 25 and use the user Maven cache");
        require(buildText.contains("mvnw.cmd")
                        && buildText.contains("clean")
                        && buildText.contains("package")
                        && buildText.contains("%ERRORLEVEL%")
                        && buildText.contains("exit /b %exitCode%"),
                "build.cmd must delegate to mvnw.cmd clean package");
        require(ignoreText.contains("target/")
                        && ignoreText.contains(".idea/")
                        && ignoreText.contains("*.iml"),
                "generated and IDE output must be ignored");
        require(Files.isDirectory(root.resolve("src"))
                        && Files.isDirectory(root.resolve("resourcepack"))
                        && Files.isDirectory(root.resolve("docs")),
                "source-owned directories must remain in place");

        System.out.println("BuildContractChecks: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
