package recafx.ghidra;

import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Blocking operations belong on a worker. cancel() is safe from the UI thread. */
public final class GhidraSession implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private final GhidraRuntime runtime;
    private final Path home, directory, project, log;
    private final Process process;
    private final FileChannel lockChannel;
    private final FileLock projectLock;
    private volatile String activeId;
    private volatile boolean closed;
    private JsonObject summary;

    private GhidraSession(GhidraRuntime runtime, Path home, Path directory, Path project, Process process,
                          FileChannel channel, FileLock lock) {
        this.runtime=runtime; this.home=home; this.directory=directory; this.project=project; this.process=process;
        lockChannel=channel; projectLock=lock; log=directory.resolve("process.log");
    }
    public static GhidraSession open(GhidraRuntime runtime, Path source, Consumer<String> progress) throws Exception {
        return open(runtime,source,"","",progress);
    }
    public static GhidraSession open(GhidraRuntime runtime, Path source, String language, String compiler, Consumer<String> progress) throws Exception {
        Path home = runtime.installed();
        if (home == null) throw new IOException("Install Ghidra or choose an existing Ghidra folder first.");
        if (!Files.isRegularFile(source)) throw new IOException("Choose a file to analyze.");
        Path sessions = runtime.data().resolve("sessions"); Files.createDirectories(sessions);
        Path directory = Files.createDirectory(sessions.resolve(UUID.randomUUID().toString()));
        Path input = directory.resolve("input.bin");
        try {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        progress.accept("Reading " + source.getFileName());
        try (var from = Files.newInputStream(source); var to = Files.newOutputStream(input,StandardOpenOption.CREATE_NEW)) {
            byte[] bytes = new byte[1024*1024]; int size;
            while ((size=from.read(bytes)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Import cancelled");
                sha.update(bytes,0,size); to.write(bytes,0,size);
            }
        }
        String contentHash = HexFormat.of().formatHex(sha.digest());
        sha.update((language+"\n"+compiler).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String key = contentHash+"-"+HexFormat.of().formatHex(sha.digest()).substring(0,12);
        Path project = runtime.data().resolve("projects").resolve(key); Files.createDirectories(project);
        FileChannel channel = FileChannel.open(project.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try { lock = channel.tryLock(); } catch (Exception error) { channel.close(); throw new IOException("This Ghidra project is already open.",error); }
        if (lock == null) { channel.close(); throw new IOException("This Ghidra project is already open."); }
        Process process = null;
        try {
            Path script = directory.resolve("LightningBridge.java");
            try (var resource = GhidraSession.class.getResourceAsStream("/ghidra/LightningBridge.java")) {
                if (resource == null) throw new IOException("Ghidra bridge is missing from LightningRecaf");
                Files.copy(resource,script);
            }
            boolean existing = Files.isRegularFile(project.resolve("analysis.gpr"));
            if (!existing) Files.writeString(project.resolve("import.incomplete"),"");
            // Finish the import before opening the interactive session. A post-import script
            // cannot save the unsaved Loaded<Program> itself without creating duplicate files.
            if (!existing || Files.exists(project.resolve("import.incomplete"))) {
                List<String> importing = runtime.command(home,false);
                importing.addAll(List.of(project.toString(),"analysis","-import",input.toString(),"-noanalysis","-max-cpu","2"));
                if (existing) importing.add("-overwrite");
                if (!language.isBlank()) importing.addAll(List.of("-processor",language));
                if (!compiler.isBlank()) importing.addAll(List.of("-cspec",compiler));
                progress.accept("Importing into Ghidra");
                process = new ProcessBuilder(importing).directory(home.toFile()).redirectErrorStream(true)
                        .redirectOutput(directory.resolve("import.log").toFile()).start();
                if (!process.waitFor(5,TimeUnit.MINUTES)) throw new IOException("Ghidra import timed out. Log: " + directory.resolve("import.log"));
                if (process.exitValue()!=0) throw new IOException("Ghidra could not import this file. Log: " + directory.resolve("import.log"));
            }
            Files.deleteIfExists(input);
            List<String> command = runtime.command(home,false);
            command.addAll(List.of(project.toString(),"analysis","-process","input.bin"));
            command.addAll(List.of("-noanalysis","-max-cpu","2","-scriptPath",directory.toString(),
                    "-postScript","LightningBridge.java",directory.toString(),Long.toString(ProcessHandle.current().pid()),
                    "-log",directory.resolve("ghidra.log").toString(),"-scriptlog",directory.resolve("script.log").toString()));
            progress.accept("Opening saved Ghidra analysis");
            process = new ProcessBuilder(command).directory(home.toFile()).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("process.log").toFile()).start();
            GhidraSession session = new GhidraSession(runtime,home,directory,project,process,channel,lock);
            session.summary = session.waitJson(directory.resolve("ready.json"),Duration.ofMinutes(5),progress);
            Files.deleteIfExists(project.resolve("import.incomplete"));
            return session;
        } catch (Exception error) {
            try { if (process != null) stop(process); }
            finally { lock.close(); channel.close(); }
            throw error;
        }
        } finally {
            Files.deleteIfExists(input);
        }
    }
    public JsonObject summary() { return summary.deepCopy(); }
    public Path project() { return project.resolve("analysis.gpr"); }
    public Path log() { return log; }
    public boolean isAlive() { return !closed && process.isAlive(); }
    public synchronized JsonElement request(JsonObject request, Consumer<String> progress) throws Exception {
        if (!isAlive()) throw failure("The Ghidra session has closed");
        String id = UUID.randomUUID().toString();
        activeId = id;
        int seconds = request.has("timeout") ? Math.max(1,Math.min(3600,request.get("timeout").getAsInt())) : 120;
        request = request.deepCopy(); request.addProperty("timeout",seconds);
        Path response = directory.resolve(id+".response");
        try {
            Path staging = directory.resolve(id+".pending"); Files.writeString(staging,JSON.toJson(request));
            Files.move(staging,directory.resolve(id+".request"),StandardCopyOption.ATOMIC_MOVE);
            JsonObject result = waitJson(response,Duration.ofSeconds(seconds+15),progress);
            if (!result.get("ok").getAsBoolean()) {
                if (result.has("cancelled") && result.get("cancelled").getAsBoolean()) throw new InterruptedIOException("Ghidra operation cancelled or timed out");
                throw new IOException(result.get("error").getAsString());
            }
            return result.get("result");
        } catch (InterruptedException | java.util.concurrent.TimeoutException error) {
            cancel();
            // An unresponsive analyzer cannot hold Recaf's worker indefinitely.
            long end = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(response) && process.isAlive() && System.nanoTime()<end) Thread.sleep(50);
            if (!Files.exists(response)) stop(process);
            throw error;
        } finally {
            activeId = null; Files.deleteIfExists(response);
        }
    }
    public JsonElement request(String operation, Consumer<String> progress) throws Exception {
        JsonObject request = new JsonObject(); request.addProperty("op",operation); return request(request,progress);
    }
    public void cancel() {
        String id = activeId;
        if (id != null) try { Files.writeString(directory.resolve(id+".cancel"),""); } catch (IOException ignored) { }
    }
    private JsonObject waitJson(Path path, Duration timeout, Consumer<String> progress) throws Exception {
        long end = System.nanoTime()+timeout.toNanos(), cancelDeadline = Long.MAX_VALUE; String last = "";
        while (!Files.isRegularFile(path)) {
            if (!process.isAlive()) throw failure("Ghidra exited with code " + process.exitValue());
            if (System.nanoTime()>end) throw new java.util.concurrent.TimeoutException("Ghidra did not respond in time. Log: " + log);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Ghidra request cancelled");
            String id = activeId;
            if (id != null && Files.exists(directory.resolve(id+".cancel"))) {
                if (cancelDeadline == Long.MAX_VALUE) cancelDeadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                if (System.nanoTime()>cancelDeadline) {
                    stop(process);
                    throw new InterruptedIOException("The analyzer did not respond to cancellation. Reopen the last saved Ghidra project.");
                }
            }
            Path status = directory.resolve("progress.json");
            if (Files.isRegularFile(status)) try {
                JsonObject state = JsonParser.parseString(Files.readString(status)).getAsJsonObject();
                if (Objects.equals(activeId,state.get("id").getAsString())) {
                    String message = state.get("message").getAsString();
                    if (!message.equals(last)) { progress.accept(message); last=message; }
                }
            } catch (IOException | JsonParseException ignored) { }
            Thread.sleep(40);
        }
        if (Files.size(path)>64L*1024*1024) throw failure("Ghidra response is too large");
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    private IOException failure(String message) { return new IOException(message + ". Log: " + log); }
    private static void stop(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(3,TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); process.waitFor(3,TimeUnit.SECONDS);
        }
    }
    @Override public synchronized void close() throws Exception {
        if (closed) return;
        try {
            if (process.isAlive()) {
                request("close",ignored -> { });
                if (!process.waitFor(45,TimeUnit.SECONDS)) throw failure("Ghidra did not finish saving its project");
                if (process.exitValue()!=0) throw failure("Ghidra could not save its project");
            }
        } finally {
            closed=true;
            try { if (process.isAlive()) stop(process); }
            finally { projectLock.close(); lockChannel.close(); }
        }
    }
    public Process openFullWorkbench() throws Exception {
        close();
        List<String> command = runtime.command(home,true); command.add(project().toString());
        return new ProcessBuilder(command).directory(home.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("workbench.log").toFile()).start();
    }
}
