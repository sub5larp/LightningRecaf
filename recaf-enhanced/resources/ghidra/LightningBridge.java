// LightningRecaf's persistent, local Ghidra analysis session.
// @category LightningRecaf
import com.google.gson.*;
import ghidra.app.script.GhidraScript;
import ghidra.app.script.GhidraScriptUtil;
import ghidra.app.script.GhidraState;
import ghidra.app.cmd.function.ApplyFunctionSignatureCmd;
import ghidra.app.util.parser.FunctionSignatureParser;
import generic.jar.ResourceFile;
import ghidra.app.decompiler.*;
import ghidra.app.plugin.core.analysis.AutoAnalysisManager;
import ghidra.app.util.exporter.Exporter;
import ghidra.program.model.address.*;
import ghidra.program.model.block.*;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.*;
import ghidra.util.classfinder.ClassSearcher;
import ghidra.util.task.TaskMonitorAdapter;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class LightningBridge extends GhidraScript {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private Path channel;
    private DecompInterface decompiler;
    private boolean closing;
    private volatile TaskMonitorAdapter active;
    private volatile String activeId;
    private volatile long deadline;
    private ProcessHandle parent;

    @Override protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 2 || currentProgram == null) throw new IllegalArgumentException("Missing analysis session");
        channel = Path.of(args[0]).toRealPath();
        parent = ProcessHandle.of(Long.parseLong(args[1])).orElseThrow();
        // End GhidraScript's encompassing transaction: each edit gets its own rollback boundary.
        end(true);
        decompiler = new DecompInterface();
        DecompileOptions options = new DecompileOptions();
        options.grabFromProgram(currentProgram);
        decompiler.setOptions(options);
        if (!decompiler.openProgram(currentProgram)) throw new IllegalStateException(decompiler.getLastMessage());
        ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "lightning-ghidra-progress"); thread.setDaemon(true); return thread;
        });
        watcher.scheduleWithFixedDelay(() -> {
            TaskMonitorAdapter task = active;
            String id = activeId;
            if (task == null || id == null) return;
            try {
                if (!parent.isAlive() || Files.exists(channel.resolve(id + ".cancel")) || System.nanoTime() > deadline) task.cancel();
                write("progress.json", Map.of("id", id, "message", Objects.toString(task.getMessage(), ""),
                        "progress", task.getProgress(), "maximum", task.getMaximum(), "cancelled", task.isCancelled()));
            } catch (Exception ignored) { }
        }, 0, 150, TimeUnit.MILLISECONDS);
        try {
            write("ready.json", summary());
            while (!closing && parent.isAlive()) {
                Path request;
                try (var files = Files.list(channel)) {
                    request = files.filter(p -> p.getFileName().toString().matches("[0-9a-f-]{36}\\.request"))
                            .sorted().findFirst().orElse(null);
                }
                if (request == null) { Thread.sleep(40); continue; }
                String id = request.getFileName().toString().replace(".request", "");
                deadline = Long.MAX_VALUE;
                activeId = id;
                active = new TaskMonitorAdapter(true);
                monitor = active;
                boolean committed = false;
                int transaction = -1;
                Map<String,Object> reply;
                try {
                    if (Files.size(request) > 1024 * 1024) throw new IllegalArgumentException("Request is too large");
                    JsonObject body = JsonParser.parseString(Files.readString(request)).getAsJsonObject();
                    String op = text(body, "op", "");
                    int seconds = Math.max(1, Math.min(3600, integer(body, "timeout", 120)));
                    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
                    if (Set.of("analyze", "options", "rename", "comment", "signature", "script", "patch", "disassemble", "function").contains(op))
                        transaction = currentProgram.startTransaction("LightningRecaf: " + op);
                    Object result = dispatch(op, body);
                    monitor.checkCancelled();
                    committed = true;
                    reply = Map.of("ok", true, "result", result);
                } catch (Exception error) {
                    reply = Map.of("ok", false, "cancelled", active.isCancelled(),
                            "error", Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
                } finally {
                    if (transaction != -1) {
                        currentProgram.endTransaction(transaction, committed);
                        decompiler.flushCache();
                    }
                    active = null; activeId = null;
                    Files.deleteIfExists(request);
                    Files.deleteIfExists(channel.resolve(id + ".cancel"));
                }
                write(id + ".response", reply);
            }
        } finally {
            watcher.shutdownNow();
            decompiler.dispose();
            // HeadlessAnalyzer saves the imported program after this script returns.
            monitor = new TaskMonitorAdapter(true);
        }
    }

    private Object dispatch(String op, JsonObject request) throws Exception {
        return switch (op) {
            case "summary" -> summary();
            case "functions" -> functions(request);
            case "decompile" -> decompile(request);
            case "listing" -> listing(request);
            case "references" -> references(request);
            case "graph" -> graph(request);
            case "strings" -> strings(request);
            case "symbols" -> symbols(request);
            case "types" -> types(request);
            case "memory" -> memory();
            case "analyzers" -> getCurrentAnalysisOptionsAndValues(currentProgram);
            case "options" -> {
                Map<String,String> values = new LinkedHashMap<>();
                request.getAsJsonObject("values").entrySet().forEach(e -> values.put(e.getKey(), e.getValue().getAsString()));
                setAnalysisOptions(currentProgram, values);
                yield getCurrentAnalysisOptionsAndValues(currentProgram);
            }
            case "analyze" -> {
                analyzeAll(currentProgram);
                monitor.checkCancelled();
                yield summary();
            }
            case "rename" -> {
                Address at = address(request);
                Function function = getFunctionAt(at);
                if (function != null) function.setName(text(request,"name",""), SourceType.USER_DEFINED);
                else createLabel(at, text(request,"name",""), true, SourceType.USER_DEFINED);
                yield summary();
            }
            case "comment" -> { setPlateComment(address(request), text(request,"value","")); yield true; }
            case "signature" -> {
                Function function = selectedFunction(request);
                var parser = new FunctionSignatureParser(currentProgram.getDataTypeManager(),
                        AutoAnalysisManager.getAnalysisManager(currentProgram).getDataTypeManagerService());
                var signature = parser.parse(function.getSignature(), text(request,"value",""));
                var command = new ApplyFunctionSignatureCmd(function.getEntryPoint(),signature,SourceType.USER_DEFINED,true,true);
                if (!command.applyTo(currentProgram,monitor)) throw new IllegalArgumentException(command.getStatusMsg());
                yield function(function);
            }
            case "script" -> script(request);
            case "patch" -> {
                byte[] bytes = HexFormat.of().parseHex(text(request,"bytes","").replaceAll("\\s", ""));
                if (bytes.length == 0 || bytes.length > 65536) throw new IllegalArgumentException("Patch must contain 1–65536 bytes");
                Address at = address(request);
                clearListing(at, at.addNoWrap(bytes.length - 1));
                setBytes(at, bytes);
                yield Map.of("address", at.toString(), "bytes", bytes.length);
            }
            case "disassemble" -> disassemble(address(request));
            case "function" -> {
                Function function = createFunction(address(request), text(request,"name",null));
                if (function == null) throw new IllegalArgumentException("Cannot create function at this address");
                yield function(function);
            }
            case "exporters" -> exporters();
            case "export" -> export(request);
            case "save" -> {
                currentProgram.save("LightningRecaf", monitor);
                yield true;
            }
            case "close" -> { currentProgram.save("LightningRecaf", monitor); closing = true; yield true; }
            default -> throw new IllegalArgumentException("Unknown Ghidra operation: " + op);
        };
    }

    private Map<String,Object> summary() {
        return Map.of("name", currentProgram.getName(), "format", currentProgram.getExecutableFormat(),
                "language", currentProgram.getLanguageID().toString(), "compiler", currentProgram.getCompilerSpec().getCompilerSpecID().toString(),
                "imageBase", currentProgram.getImageBase().toString(), "functions", currentProgram.getFunctionManager().getFunctionCount(),
                "ghidra", getGhidraVersion(), "changed", currentProgram.isChanged());
    }
    private Map<String,Object> function(Function f) {
        return Map.of("address", f.getEntryPoint().toString(), "name", f.getName(true), "signature", f.getPrototypeString(false,false),
                "size", f.getBody().getNumAddresses(), "external", f.isExternal(), "thunk", f.isThunk());
    }
    private Object functions(JsonObject request) throws Exception {
        Page page = new Page(request);
        var iterator = currentProgram.getFunctionManager().getFunctions(true);
        while (iterator.hasNext()) {
            monitor.checkCancelled(); Function f = iterator.next();
            if (page.matches(f.getName(true),f.getEntryPoint().toString()) && !page.add(function(f))) break;
        }
        return page.result();
    }
    private Function selectedFunction(JsonObject request) {
        Function function = getFunctionContaining(address(request));
        if (function == null) function = getFunctionAt(address(request));
        if (function == null) throw new IllegalArgumentException("No function at this address; run analysis or create one first");
        return function;
    }
    private Object decompile(JsonObject request) throws Exception {
        Function function = selectedFunction(request);
        DecompileResults result = decompiler.decompileFunction(function, Math.max(1, Math.min(120, integer(request,"timeout",30))), monitor);
        if (!result.decompileCompleted() || result.getDecompiledFunction() == null)
            throw new IllegalStateException(result.getErrorMessage());
        List<String> pcode = new ArrayList<>();
        HighFunction high = result.getHighFunction();
        if (high != null) {
            var operations = high.getPcodeOps();
            while (operations.hasNext() && pcode.size() < 20000) {
                monitor.checkCancelled(); pcode.add(operations.next().toString());
            }
        }
        return Map.of("function", function(function), "code", result.getDecompiledFunction().getC(),
                "signature", result.getDecompiledFunction().getSignature(), "pcode", pcode,
                "warning", Objects.toString(result.getErrorMessage(), ""), "pcodeLimit", 20000);
    }
    private Object listing(JsonObject request) throws Exception {
        Address at = address(request);
        Page page = new Page(request);
        var units = currentProgram.getListing().getCodeUnits(at, true);
        while (units.hasNext()) {
            monitor.checkCancelled(); CodeUnit unit = units.next();
            List<String> pcode = new ArrayList<>();
            if (unit instanceof Instruction instruction) for (PcodeOp op : instruction.getPcode()) pcode.add(op.toString());
            byte[] bytes = new byte[Math.min(32, unit.getLength())];
            currentProgram.getMemory().getBytes(unit.getAddress(),bytes);
            if (!page.add(Map.of("address",unit.getAddress().toString(), "text",unit.toString(),
                    "bytes",HexFormat.of().formatHex(bytes), "bytesTruncated",unit.getLength()>bytes.length, "pcode",pcode,
                    "comment",Objects.toString(unit.getComment(CommentType.PLATE),"")))) break;
        }
        return page.result();
    }
    private Object references(JsonObject request) throws Exception {
        Address at = address(request); Page page = new Page(request);
        var manager = currentProgram.getReferenceManager();
        boolean incoming = !text(request,"direction","in").equals("out");
        Iterator<Reference> refs = incoming ? manager.getReferencesTo(at) : Arrays.asList(manager.getReferencesFrom(at)).iterator();
        while (refs.hasNext()) {
            monitor.checkCancelled(); Reference ref = refs.next();
            if (!page.add(Map.of("from",ref.getFromAddress().toString(), "to",ref.getToAddress().toString(),
                    "type",ref.getReferenceType().toString(), "operand",ref.getOperandIndex()))) break;
        }
        return page.result();
    }
    private Object graph(JsonObject request) throws Exception {
        Function function = selectedFunction(request);
        List<Object> nodes = new ArrayList<>(), edges = new ArrayList<>();
        var blocks = new BasicBlockModel(currentProgram).getCodeBlocksContaining(function.getBody(), monitor);
        boolean truncated = false;
        while (blocks.hasNext()) {
            monitor.checkCancelled();
            if (nodes.size() == 5000) { truncated = true; break; }
            CodeBlock block = blocks.next();
            String id = block.getFirstStartAddress().toString();
            nodes.add(Map.of("id", id, "name", block.getName(), "end", block.getMaxAddress().toString()));
            var destinations = block.getDestinations(monitor);
            while (destinations.hasNext()) {
                CodeBlockReference ref = destinations.next();
                if (edges.size() >= 20000) { truncated = true; break; }
                edges.add(Map.of("from",id,"to",ref.getDestinationAddress().toString(),"type",ref.getFlowType().toString()));
            }
        }
        return Map.of("nodes",nodes,"edges",edges,"truncated",truncated);
    }
    private Object strings(JsonObject request) throws Exception {
        Page page = new Page(request);
        var data = currentProgram.getListing().getDefinedData(true);
        while (data.hasNext()) {
            monitor.checkCancelled(); Data item = data.next();
            if (!item.hasStringValue()) continue;
            String value = String.valueOf(item.getValue());
            if (page.matches(value,item.getAddress().toString()) && !page.add(Map.of("address",item.getAddress().toString(),"value",value,
                    "type",item.getDataType().getName()))) break;
        }
        return page.result();
    }
    private Object symbols(JsonObject request) throws Exception {
        Page page = new Page(request); String kind = text(request,"kind","all");
        var symbols = currentProgram.getSymbolTable().getAllSymbols(true);
        while (symbols.hasNext()) {
            monitor.checkCancelled(); Symbol symbol = symbols.next();
            boolean exported = currentProgram.getSymbolTable().isExternalEntryPoint(symbol.getAddress());
            if (kind.equals("imports") && !symbol.isExternal() || kind.equals("exports") && !exported) continue;
            if (page.matches(symbol.getName(true),symbol.getAddress().toString()) && !page.add(Map.of("address",symbol.getAddress().toString(),
                    "name",symbol.getName(true),"type",symbol.getSymbolType().toString(),"external",symbol.isExternal(),"exported",exported))) break;
        }
        return page.result();
    }
    private Object types(JsonObject request) throws Exception {
        Page page = new Page(request); var types = currentProgram.getDataTypeManager().getAllDataTypes();
        while (types.hasNext()) {
            monitor.checkCancelled(); DataType type = types.next();
            if (page.matches(type.getPathName(),type.getDescription()) && !page.add(Map.of("name",type.getPathName(),"length",type.getLength(),
                    "description",Objects.toString(type.getDescription(),""),"definition",type.toString()))) break;
        }
        return page.result();
    }
    private Object memory() {
        List<Object> result = new ArrayList<>();
        for (var block : currentProgram.getMemory().getBlocks()) result.add(Map.of("name",block.getName(),"start",block.getStart().toString(),
                "end",block.getEnd().toString(),"size",block.getSize(),"read",block.isRead(),"write",block.isWrite(),"execute",block.isExecute()));
        return result;
    }
    private Object exporters() {
        List<Object> result = new ArrayList<>();
        for (Exporter exporter : ClassSearcher.getInstances(Exporter.class)) if (exporter.canExportDomainObject(currentProgram))
            result.add(Map.of("name",exporter.getName(),"extension",Objects.toString(exporter.getDefaultFileExtension(),"")));
        return result;
    }
    private Object script(JsonObject request) throws Exception {
        Path path = Path.of(text(request,"path","")).toRealPath();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Choose a Ghidra script file");
        ResourceFile file = new ResourceFile(path.toFile());
        // Ghidra's generated script manifest already imports its OSGi package.
        // Referencing BundleHost directly duplicates that import on 12.1.4.
        Object host = GhidraScriptUtil.class.getMethod("getBundleHost").invoke(null);
        host.getClass().getMethod("add",java.util.List.class,boolean.class,boolean.class)
                .invoke(host,List.of(new ResourceFile(path.getParent().toFile())),true,false);
        var provider = GhidraScriptUtil.getProvider(file);
        if (provider == null) throw new IllegalArgumentException("This Ghidra installation cannot run this script language");
        List<String> arguments = new ArrayList<>();
        if (request.has("args")) request.getAsJsonArray("args").forEach(arg -> arguments.add(arg.getAsString()));
        BoundedWriter captured = new BoundedWriter();
        try (var output = new java.io.PrintWriter(captured)) {
            GhidraScript script = provider.getScriptInstance(file,output);
            script.setScriptArgs(arguments.toArray(String[]::new));
            GhidraState state = new GhidraState(getState());
            if (request.has("address")) state.setCurrentAddress(address(request));
            script.execute(state,monitor,output);
        }
        return Map.of("output",captured.text.toString(),"truncated",captured.truncated);
    }
    private static final class BoundedWriter extends java.io.Writer {
        final StringBuilder text = new StringBuilder(); boolean truncated;
        @Override public void write(char[] chars,int offset,int count) {
            int keep = Math.min(count,Math.max(0,1024*1024-text.length()));
            text.append(chars,offset,keep); if (keep<count) truncated=true;
        }
        @Override public void flush() { }
        @Override public void close() { }
    }
    private Object export(JsonObject request) throws Exception {
        Path output = Path.of(text(request,"path","")).toAbsolutePath().normalize();
        if (Files.exists(output)) throw new IllegalArgumentException("Choose a new output file");
        for (Exporter exporter : ClassSearcher.getInstances(Exporter.class)) if (exporter.getName().equals(text(request,"format",""))) {
            if (!exporter.canExportDomainObject(currentProgram)) throw new IllegalArgumentException("Exporter does not support this program");
            Path staging=Files.createTempDirectory(output.getParent(),".lightning-export-");
            try {
                if (!exporter.export(staging.resolve(output.getFileName()).toFile(), currentProgram, null, monitor))
                    throw new IllegalStateException(exporter.getMessageLog().toString());
                monitor.checkCancelled();
                List<Path> files;
                try(var children=Files.list(staging)){files=children.toList();}
                if(files.isEmpty())throw new IllegalStateException("Exporter did not produce any files");
                for(Path file:files)if(Files.exists(output.resolveSibling(file.getFileName())))
                    throw new IllegalArgumentException("An export companion file already exists: "+file.getFileName());
                for(Path file:files)Files.move(file,output.resolveSibling(file.getFileName()));
                return Map.of("path",output.toString(),"message",exporter.getMessageLog().toString());
            } finally {
                try(var paths=Files.walk(staging)) {
                    for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);
                }
            }
        }
        throw new IllegalArgumentException("Exporter is unavailable");
    }
    private Address address(JsonObject request) {
        Address address = currentProgram.getAddressFactory().getAddress(text(request,"address",""));
        if (address == null) throw new IllegalArgumentException("Invalid address");
        return address;
    }
    private static String text(JsonObject o, String name, String fallback) { return o.has(name) ? o.get(name).getAsString() : fallback; }
    private static int integer(JsonObject o, String name, int fallback) { return o.has(name) ? o.get(name).getAsInt() : fallback; }
    private void write(String name, Object value) throws Exception {
        Path staging = channel.resolve(name + ".tmp");
        Files.writeString(staging, JSON.toJson(value), StandardCharsets.UTF_8);
        Files.move(staging, channel.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static class Page {
        final List<Object> rows = new ArrayList<>(); final int offset, limit; final String query; int matched; boolean more;
        Page(JsonObject request) {
            offset = Math.max(0,integer(request,"offset",0)); limit = Math.max(1,Math.min(2000,integer(request,"limit",500)));
            query = text(request,"query","").toLowerCase(Locale.ROOT);
        }
        boolean matches(String... values) { for (String value : values) if (value != null && value.toLowerCase(Locale.ROOT).contains(query)) return true; return false; }
        boolean add(Object row) {
            if (matched++ < offset) return true;
            if (rows.size() == limit) { more = true; return false; }
            rows.add(row); return true;
        }
        Object result() { return Map.of("rows",rows,"offset",offset,"more",more); }
    }
}
