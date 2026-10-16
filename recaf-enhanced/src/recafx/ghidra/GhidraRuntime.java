package recafx.ghidra;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.zip.ZipInputStream;

/** Ghidra is loaded only on demand, and never added to Recaf's class loader. */
public final class GhidraRuntime {
    public static final String VERSION = "12.1.4";
    public static final long DOWNLOAD_BYTES = 569649598L;
    public static final String SHA256 = "ddac49f903da9d5bac833e5cc79395098b9c33cfd3279be5f31bd00387d2d4db";
    private static final URI DOWNLOAD = URI.create("https://github.com/NationalSecurityAgency/ghidra/releases/download/Ghidra_12.1.4_build/ghidra_12.1.4_PUBLIC_20260921.zip");
    private final Path data;

    public GhidraRuntime(Path data) { this.data = data.toAbsolutePath().normalize(); }
    public Path data() { return data; }
    public Path installed() throws IOException {
        Path configured = data.resolve("installation.txt");
        if (Files.isRegularFile(configured)) {
            Path home = Path.of(Files.readString(configured).trim());
            validate(home); return home;
        }
        Path bundled = Path.of(System.getProperty("java.home")).getParent().resolve("ghidra");
        if (Files.isRegularFile(bundled.resolve("Ghidra/application.properties"))) { validate(bundled); return bundled; }
        Path home = data.resolve("runtime-" + VERSION).resolve("ghidra_" + VERSION + "_PUBLIC");
        if (!Files.exists(home)) return null;
        validate(home); return home;
    }
    public void useInstallation(Path home) throws IOException {
        home = home.toRealPath(); validate(home);
        Files.createDirectories(data);
        atomicWrite(data.resolve("installation.txt"), home.toString());
    }
    public SortedMap<String,List<String>> languages() throws Exception {
        Path home=installed();if(home==null)throw new IOException("Install Ghidra or choose its folder first.");
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var builder=factory.newDocumentBuilder();SortedMap<String,List<String>> result=new TreeMap<>();
        try(var paths=Files.walk(home.resolve("Ghidra/Processors"))) {
            for(Path path:paths.filter(p->p.toString().endsWith(".ldefs")).toList()) {
                if(Files.size(path)>1024*1024)continue;
                var definitions=builder.parse(path.toFile()).getElementsByTagName("language");
                for(int i=0;i<definitions.getLength();i++) {
                    var language=(org.w3c.dom.Element)definitions.item(i);List<String> compilers=new ArrayList<>();
                    var entries=language.getElementsByTagName("compiler");
                    for(int j=0;j<entries.getLength();j++)compilers.add(((org.w3c.dom.Element)entries.item(j)).getAttribute("id"));
                    result.put(language.getAttribute("id"),List.copyOf(compilers));
                }
            }
        }
        return result;
    }
    public static String validate(Path home) throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(home.resolve("Ghidra/application.properties"))) { properties.load(input); }
        if (!"Ghidra".equals(properties.getProperty("application.name")) ||
                !Files.isRegularFile(home.resolve("Ghidra/Framework/Utility/lib/Utility.jar")))
            throw new IOException("Choose the Ghidra installation folder containing ghidraRun.bat.");
        int required = Integer.parseInt(properties.getProperty("application.java.min","21"));
        if (Runtime.version().feature() < required) throw new IOException("This Ghidra installation needs Java " + required + " or newer.");
        return properties.getProperty("application.version", "unknown");
    }
    public Path install(LongConsumer progress, BooleanSupplier cancelled) throws Exception {
        Files.createDirectories(data);
        try (FileChannel channel = FileChannel.open(data.resolve("install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Ghidra is already being installed by another window.");
            Path available = installed();
            if (available != null) return available;
            Path archive = data.resolve("ghidra-" + VERSION + ".zip.partial");
            Path staging = data.resolve("install-" + UUID.randomUUID());
            try {
                download(DOWNLOAD,archive,DOWNLOAD_BYTES,SHA256,progress,cancelled,Duration.ofSeconds(60));
                Files.createDirectory(staging);
                try (var zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
                    long expanded = 0; int count = 0;
                    for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Ghidra installation cancelled");
                        if (++count > 100000) throw new IOException("Too many Ghidra archive entries");
                        Path target = safeEntry(staging,entry.getName());
                        if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                        Files.createDirectories(target.getParent());
                        try (var output = Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
                            byte[] buffer = new byte[65536]; int size;
                            while ((size = zip.read(buffer)) != -1) {
                                if (cancelled.getAsBoolean()) throw new InterruptedIOException("Ghidra installation cancelled");
                                expanded += size;
                                if (expanded > 4L*1024*1024*1024) throw new IOException("Ghidra archive exceeds its extraction limit");
                                output.write(buffer,0,size);
                            }
                        }
                    }
                }
                Path inner = staging.resolve("ghidra_" + VERSION + "_PUBLIC"); validate(inner);
                Path destination = data.resolve("runtime-" + VERSION);
                Files.move(staging,destination,StandardCopyOption.ATOMIC_MOVE);
                return destination.resolve(inner.getFileName());
            } finally {
                Files.deleteIfExists(archive);
                if (Files.exists(staging)) deleteOwned(staging,data);
            }
        }
    }
    static void download(URI source,Path archive,long expectedBytes,String expectedHash,LongConsumer progress,
                         BooleanSupplier cancelled,Duration idleTimeout) throws Exception {
        long end=System.nanoTime()+Duration.ofMinutes(20).toNanos();
        try (HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpRequest request=HttpRequest.newBuilder(source).timeout(Duration.ofMinutes(20)).header("User-Agent","LightningRecaf").GET().build();
            var pending=client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response;
            try {
                while(true) {
                    if(cancelled.getAsBoolean() || System.nanoTime()>end)throw new InterruptedIOException("Ghidra download cancelled or timed out");
                    try {response=pending.get(200,TimeUnit.MILLISECONDS);break;}catch(TimeoutException waiting) { }
                }
            } catch(Exception error) {pending.cancel(true);throw error;}
            try(var input=response.body()) {
                if(response.statusCode()!=200)throw new IOException("Ghidra download returned HTTP "+response.statusCode());
                AtomicLong lastRead=new AtomicLong(System.nanoTime());AtomicBoolean expired=new AtomicBoolean();
                var watcher=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"lightning-component-download");t.setDaemon(true);return t;});
                try {
                    watcher.scheduleWithFixedDelay(()->{
                        long now=System.nanoTime();
                        if(cancelled.getAsBoolean() || now>end || now-lastRead.get()>idleTimeout.toNanos()) {
                            expired.set(true);try {input.close();}catch(IOException ignored) { }
                        }
                    },0,100,TimeUnit.MILLISECONDS);
                    MessageDigest sha=MessageDigest.getInstance("SHA-256");long count=0;
                    try(var output=Files.newOutputStream(archive)) {
                        byte[] buffer=new byte[1024*1024];int size;
                        while((size=input.read(buffer))!=-1) {
                            if(cancelled.getAsBoolean() || expired.get())throw new InterruptedIOException("Ghidra download cancelled or timed out");
                            lastRead.set(System.nanoTime());count+=size;
                            if(count>expectedBytes)throw new IOException("Unexpected Ghidra download size");
                            sha.update(buffer,0,size);output.write(buffer,0,size);progress.accept(count);
                        }
                    } catch(IOException error) {
                        if(expired.get() || cancelled.getAsBoolean())throw new InterruptedIOException("Ghidra download cancelled or timed out");
                        throw error;
                    }
                    if(count!=expectedBytes || !HexFormat.of().formatHex(sha.digest()).equals(expectedHash))
                        throw new IOException("Ghidra download verification failed; the download was not installed.");
                } finally {watcher.shutdownNow();}
            }
        }
    }
    static Path safeEntry(Path root,String name) throws IOException {
        if (name.isEmpty() || name.contains("\\") || name.contains(":") || name.startsWith("/")) throw new IOException("Invalid component archive path");
        for (String part : name.split("/")) if (part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" "))
            throw new IOException("Invalid component archive path");
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("Invalid component archive path");
        return target;
    }
    static void deleteOwned(Path path,Path parent) throws IOException {
        Path normalized = path.toAbsolutePath().normalize(), boundary = parent.toAbsolutePath().normalize();
        if (normalized.equals(boundary) || !normalized.startsWith(boundary)) throw new IOException("Cleanup path is outside component storage");
        try (var paths = Files.walk(normalized)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }
    static void atomicWrite(Path path,String content) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName()+"."+UUID.randomUUID()+".tmp");
        try {
            Files.writeString(temporary,content,StandardOpenOption.CREATE_NEW);
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    public List<String> command(Path home,boolean graphical) throws IOException {
        validate(home);
        String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin",binary).toString(),
                "-Xmx2G", "-XX:ParallelGCThreads=2", "-XX:CICompilerCount=2", "-Djava.system.class.loader=ghidra.GhidraClassLoader",
                "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US", "-Dsun.java2d.d3d=false",
                "-Dlog4j.skipJansi=true", "-Djavax.xml.accessExternalDTD=", "-Djavax.xml.accessExternalSchema=",
                "-Djavax.xml.accessExternalStylesheet=", "--enable-native-access=ALL-UNNAMED"));
        if (!graphical) command.add("-Djava.awt.headless=true");
        command.addAll(List.of("-cp",home.resolve("Ghidra/Framework/Utility/lib/Utility.jar").toString(),"ghidra.Ghidra",
                graphical ? "ghidra.GhidraRun" : "ghidra.app.util.headless.AnalyzeHeadless"));
        return command;
    }
}
