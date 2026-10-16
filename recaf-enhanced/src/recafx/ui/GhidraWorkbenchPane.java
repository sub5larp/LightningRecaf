package recafx.ui;

import com.google.gson.*;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.*;
import recafx.ghidra.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** On-demand native workbench. All Ghidra, disk and installation work runs off the FX thread. */
public final class GhidraWorkbenchPane extends BorderPane implements AutoCloseable {
    private final GhidraRuntime runtime;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r,"lightning-native-workbench"); thread.setDaemon(true); return thread;
    });
    private final BooleanProperty busy = new SimpleBooleanProperty();
    private final BooleanProperty ready = new SimpleBooleanProperty();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Label status = new Label("Choose a file to analyze with Ghidra.");
    private final TableView<JsonObject> functions = table("address","name","signature");
    private final TextField filter = new TextField(), address = new TextField();
    private final NativeCodeView code = new NativeCodeView(true), assembly = new NativeCodeView(false), pcode = new NativeCodeView(false);
    private final TextArea scriptOutput = new TextArea();
    private final TabPane tabs = new TabPane();
    private final ScrollPane graph = new ScrollPane();
    private final Map<String,TableView<JsonObject>> dataTables = new LinkedHashMap<>();
    private volatile GhidraSession session;
    private volatile Thread runningThread;
    private volatile boolean disposed;
    private int functionOffset;
    private String selectedAddress;
    private String importLanguage="", importCompiler="";
    private boolean autoAnalyze=true;

    public GhidraWorkbenchPane(Path data) {
        runtime = new GhidraRuntime(data.resolve("ghidra"));
        setPadding(new Insets(6));
        Button open = button("Open file…",this::chooseFile,false);
        Button imports = button("Import options…",this::importOptions,false);
        Button analyze = button("Analyze",this::analyze,true);
        Button save = button("Save",() -> execute("Saving analysis",() -> call("save"),r -> status.setText("Analysis saved.")),true);
        Button export = button("Export…",this::chooseExport,true);
        Button script = button("Run script…",this::chooseScript,true);
        Button full = button("Full Ghidra workbench",() -> execute("Saving and opening Ghidra",() -> {
            Process process = requireSession().openFullWorkbench(); session=null; return process.pid();
        },ignored -> { ready.set(false); status.setText("Opened the saved project in Ghidra."); }),true);
        Button choose = button("Ghidra folder…",this::chooseInstallation,false);
        Button install = button("Install Ghidra (544 MB)",this::install,false);
        Button cancel = new Button("Cancel"); cancel.disableProperty().bind(busy.not()); cancel.setOnAction(e -> cancel());
        FlowPane toolbar = new FlowPane(6,6,open,imports,analyze,save,export,script,full,choose,install,cancel);
        filter.setPromptText("Filter functions"); filter.setOnAction(e -> {functionOffset=0; refreshFunctions();});
        Button search = button("Find",() -> {functionOffset=0;refreshFunctions();},true);
        HBox searchBar = new HBox(5,filter,search); HBox.setHgrow(filter,Priority.ALWAYS);
        Button previous = button("Previous",() -> {functionOffset=Math.max(0,functionOffset-500);refreshFunctions();},true);
        Button next = button("Next",() -> {functionOffset+=500;refreshFunctions();},true);
        VBox explorer = new VBox(6,searchBar,functions,new HBox(6,previous,next)); VBox.setVgrow(functions,Priority.ALWAYS);
        functions.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        functions.setOnMouseClicked(e -> {if(e.getClickCount()==2)openFunction();});
        functions.setOnKeyPressed(e -> {if(e.getCode()==javafx.scene.input.KeyCode.ENTER)openFunction();});
        ContextMenu menu = new ContextMenu();
        MenuItem inspect = new MenuItem("Open function");inspect.setOnAction(e -> openFunction());
        MenuItem rename = new MenuItem("Rename function…");rename.setOnAction(e -> rename());
        MenuItem comment = new MenuItem("Edit comment…");comment.setOnAction(e -> comment());
        MenuItem signature = new MenuItem("Edit signature…");signature.setOnAction(e -> signature());
        menu.getItems().addAll(inspect,rename,signature,comment);functions.setContextMenu(menu);
        tab("Decompiled",code);tab("Assembly",assembly);tab("P-code",pcode);tab("Flow graph",graph);
        dataTab("References","references","from","to","type");
        dataTab("Strings","strings","address","value","type");
        dataTab("Symbols","symbols","address","name","type");
        dataTab("Imports","imports","address","name","type");
        dataTab("Exports","exports","address","name","type");
        dataTab("Types","types","name","length","definition");
        dataTab("Memory","memory","name","start","end","size","read","write","execute");
        TableView<JsonObject> options = dataTab("Analysis options","analyzers","name","value");
        options.setOnMouseClicked(e -> {if(e.getClickCount()==2)editOption(options.getSelectionModel().getSelectedItem());});
        scriptOutput.setEditable(false);tab("Script output",scriptOutput);
        address.setPromptText("Go to address (hex)");address.setOnAction(e -> navigate(address.getText()));
        Button go = button("Go",() -> navigate(address.getText()),true);
        Button patch = button("Patch bytes…",this::patch,true);
        HBox navigation = new HBox(6,address,go,patch);HBox.setHgrow(address,Priority.ALWAYS);
        VBox viewer = new VBox(6,navigation,tabs);VBox.setVgrow(tabs,Priority.ALWAYS);
        SplitPane split = new SplitPane(explorer,viewer);split.setDividerPositions(0.30);
        setTop(toolbar);setCenter(split);setBottom(status);BorderPane.setMargin(split,new Insets(8,0,8,0));
        status.setWrapText(true);
        setOnDragOver(event -> {if(event.getDragboard().hasFiles() && !busy.get())event.acceptTransferModes(TransferMode.COPY);event.consume();});
        setOnDragDropped(event -> {
            boolean accepted=!busy.get() && event.getDragboard().hasFiles();
            if(accepted)open(event.getDragboard().getFiles().getFirst().toPath());
            event.setDropCompleted(accepted);event.consume();
        });
    }
    private Window window() { return getScene()==null ? null : getScene().getWindow(); }
    private Button button(String title,Runnable action,boolean needsSession) {
        Button button = new Button(title);button.setOnAction(e -> action.run());
        button.disableProperty().bind(needsSession ? busy.or(ready.not()) : busy);return button;
    }
    private void chooseFile() {
        FileChooser chooser = new FileChooser();chooser.setTitle("Open in Ghidra");
        chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Programs and libraries","*.exe","*.dll","*.sys","*.so","*.elf","*.dylib","*.class","*.dex"),new FileChooser.ExtensionFilter("All files","*.*"));
        var file=chooser.showOpenDialog(window());if(file!=null)open(file.toPath());
    }
    public void open(Path path) {
        open(path,false);
    }
    private void open(Path path,boolean temporary) {
        if(busy.get() || disposed)return;
        ready.set(false);functions.getItems().clear();code.clear();assembly.clear();pcode.clear();graph.setContent(null);selectedAddress=null;
        execute("Opening "+path.getFileName(),() -> {
            if(session!=null){session.close();session=null;}
            try { session=GhidraSession.open(runtime,path,importLanguage,importCompiler,this::progress); }
            finally { if(temporary)Files.deleteIfExists(path); }
            JsonObject request=new JsonObject();request.addProperty("op","analyze");request.addProperty("timeout",300);
            if(autoAnalyze && !Files.isRegularFile(session.project().resolveSibling("analyzed"))) {
                session.request(request,this::progress);call("save");
                Files.writeString(session.project().resolveSibling("analyzed"),"");
            }
            return call("functions").getAsJsonObject();
        },result -> {
            ready.set(true);functionOffset=0;showRows(functions,result);
            status.setText(path.getFileName()+" · "+session.summary().get("language").getAsString()+" · Double-click a function to open it.");
        });
    }
    private void importOptions() {
        execute("Loading supported processors",runtime::languages,languages -> {
            Dialog<Boolean> dialog=new Dialog<>();dialog.initOwner(window());dialog.setTitle("Import options");
            ComboBox<String> language=new ComboBox<>();language.getItems().add("Automatic");language.getItems().addAll(languages.keySet());
            language.setValue(importLanguage.isEmpty()?"Automatic":importLanguage);language.setMaxWidth(Double.MAX_VALUE);
            ComboBox<String> compiler=new ComboBox<>();compiler.setMaxWidth(Double.MAX_VALUE);
            Runnable update=()->{compiler.getItems().setAll("Automatic");compiler.getItems().addAll(languages.getOrDefault(language.getValue(),List.of()));compiler.setValue("Automatic");};
            update.run();if(!importCompiler.isEmpty())compiler.setValue(importCompiler);language.setOnAction(e->update.run());
            CheckBox analyze=new CheckBox("Analyze new imports automatically");analyze.setSelected(autoAnalyze);
            dialog.getDialogPane().setContent(new VBox(8,new Label("Processor (select one for raw binaries)"),language,new Label("Compiler"),compiler,analyze));
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);dialog.setResultConverter(b->b==ButtonType.OK);
            dialog.showAndWait().filter(Boolean::booleanValue).ifPresent(ok->{importLanguage=language.getValue().equals("Automatic")?"":language.getValue();importCompiler=compiler.getValue().equals("Automatic")?"":compiler.getValue();autoAnalyze=analyze.isSelected();});
        });
    }
    public void openBytes(String name,byte[] bytes) {
        if(busy.get() || disposed)return;
        execute("Preparing "+name,() -> {
            Path files=runtime.data().resolve("workspace-inputs");Files.createDirectories(files);
            Path path=Files.createTempFile(files,"input-",".bin");Files.write(path,bytes);return path;
        },path -> open(path,true));
    }
    private void chooseInstallation() {
        DirectoryChooser chooser=new DirectoryChooser();chooser.setTitle("Choose the folder containing ghidraRun.bat");
        var folder=chooser.showDialog(window());if(folder==null)return;
        execute("Checking Ghidra",() -> {runtime.useInstallation(folder.toPath());return GhidraRuntime.validate(folder.toPath());},version -> status.setText("Ghidra "+version+" is ready."));
    }
    private void install() {
        execute("Downloading Ghidra",() -> runtime.install(bytes -> progress("Downloading Ghidra · "+(bytes*100/GhidraRuntime.DOWNLOAD_BYTES)+"%"),cancelled::get),
                home -> status.setText("Ghidra is installed. Open a file to analyze it."));
    }
    private void analyze() {
        execute("Analyzing",() -> {JsonObject request=command("analyze");request.addProperty("timeout",300);requireSession().request(request,this::progress);call("save");return call("functions").getAsJsonObject();},
                result -> {showRows(functions,result);status.setText("Analysis complete.");});
    }
    private void refreshFunctions() {
        if(!ready.get() || busy.get())return;
        JsonObject request=command("functions");request.addProperty("query",filter.getText());request.addProperty("offset",functionOffset);
        execute("Finding functions",() -> requireSession().request(request,this::progress).getAsJsonObject(),result -> showRows(functions,result));
    }
    private void openFunction() {
        JsonObject selected=functions.getSelectionModel().getSelectedItem();
        if(selected!=null)navigate(string(selected,"address"));
    }
    private void navigate(String target) {
        if(!ready.get() || busy.get() || target==null || target.isBlank())return;
        selectedAddress=target;address.setText(target);code.setText("Decompiling…");assembly.clear();pcode.clear();
        execute("Opening "+target,() -> {
            Map<String,JsonElement> result=new HashMap<>();
            try{result.put("decompile",at("decompile",target));}catch(java.io.IOException error){result.put("error",new JsonPrimitive(error.getMessage()));}
            result.put("listing",at("listing",target));result.put("references",at("references",target));
            if(!result.containsKey("error"))result.put("graph",at("graph",target));
            return result;
        },result -> {
            if(result.containsKey("decompile")){
                JsonObject decompiled=result.get("decompile").getAsJsonObject();code.setText(string(decompiled,"code"));
                StringBuilder text=new StringBuilder();decompiled.getAsJsonArray("pcode").forEach(row -> text.append(row.getAsString()).append('\n'));pcode.setText(text.toString());
            }else code.setText(result.get("error").getAsString());
            StringBuilder listing=new StringBuilder();for(JsonElement row:result.get("listing").getAsJsonObject().getAsJsonArray("rows")){
                JsonObject unit=row.getAsJsonObject();listing.append(string(unit,"address")).append("  ").append(string(unit,"bytes")).append("  ").append(string(unit,"text")).append('\n');
            }
            assembly.setText(listing.toString());showRows(dataTables.get("references"),result.get("references").getAsJsonObject());
            graph.setContent(result.containsKey("graph") ? new GhidraFlowGraph(result.get("graph").getAsJsonObject(),this::navigate) : new Label("No function at this address."));
            status.setText("Address "+target);tabs.getSelectionModel().selectFirst();
        });
    }
    private void rename() {
        JsonObject selected=functions.getSelectionModel().getSelectedItem();if(selected==null || busy.get())return;
        prompt("Rename function",string(selected,"name"),name -> edit("rename",string(selected,"address"),"name",name));
    }
    private void comment() {
        JsonObject selected=functions.getSelectionModel().getSelectedItem();if(selected==null || busy.get())return;
        prompt("Function comment","",value -> edit("comment",string(selected,"address"),"value",value));
    }
    private void signature() {
        JsonObject selected=functions.getSelectionModel().getSelectedItem();if(selected==null || busy.get())return;
        prompt("Function signature",string(selected,"signature"),value -> edit("signature",string(selected,"address"),"value",value));
    }
    private void chooseScript() {
        FileChooser chooser=new FileChooser();chooser.setTitle("Run a Ghidra script");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Ghidra scripts","*.java","*.py"));
        var file=chooser.showOpenDialog(window());if(file==null)return;
        Dialog<String> dialog=new Dialog<>();dialog.initOwner(window());dialog.setTitle("Run "+file.getName());
        dialog.setHeaderText("Script arguments (one per line)");TextArea arguments=new TextArea();arguments.setPrefRowCount(5);
        dialog.getDialogPane().setContent(arguments);ButtonType run=new ButtonType("Run",ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(run,ButtonType.CANCEL);dialog.setResultConverter(button -> button==run?arguments.getText():null);
        dialog.showAndWait().ifPresent(text -> {
            JsonObject request=command("script");request.addProperty("path",file.toString());request.addProperty("timeout",300);
            JsonArray values=new JsonArray();if(!text.isEmpty())text.lines().forEach(values::add);request.add("args",values);
            if(selectedAddress!=null)request.addProperty("address",selectedAddress);
            execute("Running "+file.getName(),() -> {JsonObject result=requireSession().request(request,this::progress).getAsJsonObject();call("save");return result;},result -> {
                scriptOutput.setText(string(result,"output")+(result.get("truncated").getAsBoolean()?"\n[Output truncated at 1 MB]":""));
                tabs.getSelectionModel().select(tabs.getTabs().stream().filter(tab->tab.getContent()==scriptOutput).findFirst().orElseThrow());
                status.setText("Script completed and analysis saved.");
            });
        });
    }
    private void patch() {
        String target=address.getText();if(target.isBlank())return;
        prompt("Replace bytes at "+target+" (hex)","",value -> edit("patch",target,"bytes",value));
    }
    private void edit(String op,String target,String key,String value) {
        JsonObject request=command(op);request.addProperty("address",target);request.addProperty(key,value);
        execute("Updating analysis",() -> {requireSession().request(request,this::progress);call("save");return call("functions").getAsJsonObject();},
                result -> {showRows(functions,result);status.setText("Saved to the Ghidra project.");});
    }
    private void editOption(JsonObject row) {
        if(row==null || busy.get())return;
        prompt(string(row,"name"),string(row,"value"),value -> {
            JsonObject request=command("options"),values=new JsonObject();values.addProperty(string(row,"name"),value);request.add("values",values);
            execute("Updating analysis options",() -> {JsonElement result=requireSession().request(request,this::progress);call("save");return result;},
                    result -> showOptions(dataTables.get("analyzers"),result.getAsJsonObject()));
        });
    }
    private void chooseExport() {
        execute("Loading export formats",() -> call("exporters").getAsJsonArray(),formats -> {
            List<String> names=new ArrayList<>();formats.forEach(e -> names.add(string(e.getAsJsonObject(),"name")));
            ChoiceDialog<String> dialog=new ChoiceDialog<>(names.contains("C/C++")?"C/C++":names.getFirst(),names);dialog.initOwner(window());dialog.setTitle("Export analysis");dialog.setHeaderText("Choose an export format");
            dialog.showAndWait().ifPresent(format -> {
                FileChooser chooser=new FileChooser();chooser.setTitle("Export "+format);var file=chooser.showSaveDialog(window());if(file==null)return;
                JsonObject request=command("export");request.addProperty("format",format);request.addProperty("path",file.toPath().toString());request.addProperty("timeout",300);
                execute("Exporting "+format,() -> requireSession().request(request,this::progress),ignored -> status.setText("Exported to "+file));
            });
        });
    }
    private void prompt(String title,String initial,Consumer<String> apply) {
        TextInputDialog dialog=new TextInputDialog(initial);dialog.initOwner(window());dialog.setTitle(title);dialog.setHeaderText(title);dialog.showAndWait().ifPresent(apply);
    }
    private TableView<JsonObject> dataTab(String title,String operation,String...columns) {
        TableView<JsonObject> table=table(columns);dataTables.put(operation,table);
        TextField query=new TextField();query.setPromptText("Filter "+title.toLowerCase(Locale.ROOT));
        int[] offset={0};
        Runnable refresh=() -> {
            if(!ready.get() || busy.get())return;
            if(operation.equals("references") && selectedAddress==null)return;
            JsonObject request=command(operation.equals("imports") || operation.equals("exports")?"symbols":operation);
            request.addProperty("query",query.getText());request.addProperty("offset",offset[0]);
            if(operation.equals("imports") || operation.equals("exports"))request.addProperty("kind",operation);
            if(operation.equals("references"))request.addProperty("address",selectedAddress);
            execute("Loading "+title.toLowerCase(Locale.ROOT),() -> requireSession().request(request,this::progress),result -> {
                if(operation.equals("analyzers"))showOptions(table,result.getAsJsonObject());
                else if(result.isJsonArray())table.getItems().setAll(objects(result.getAsJsonArray()));
                else showRows(table,result.getAsJsonObject());
            });
        };
        query.setOnAction(e -> {offset[0]=0;refresh.run();});
        Button find=button("Find",() -> {offset[0]=0;refresh.run();},true);
        Button previous=button("Previous",() -> {offset[0]=Math.max(0,offset[0]-500);refresh.run();},true);
        Button next=button("Next",() -> {offset[0]+=500;refresh.run();},true);
        HBox search=new HBox(6,query,find,previous,next);HBox.setHgrow(query,Priority.ALWAYS);
        VBox content=new VBox(6,search,table);VBox.setVgrow(table,Priority.ALWAYS);Tab tab=tab(title,content);
        tab.setOnSelectionChanged(e -> {if(tab.isSelected())refresh.run();});
        table.setOnMouseClicked(e -> {
            if(e.getClickCount()!=2)return;JsonObject row=table.getSelectionModel().getSelectedItem();if(row==null)return;
            String target=row.has("address")?string(row,"address"):row.has("from")?string(row,"from"):row.has("start")?string(row,"start"):null;
            if(target!=null)navigate(target);
        });return table;
    }
    private void showRows(TableView<JsonObject> table,JsonObject page) {
        table.getItems().setAll(objects(page.getAsJsonArray("rows")));
        status.setText(table.getItems().size()+" entries"+(page.get("more").getAsBoolean()?" · More available on the next page":""));
    }
    private static void showOptions(TableView<JsonObject> table,JsonObject options) {
        List<JsonObject> rows=new ArrayList<>();options.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject row=new JsonObject();row.addProperty("name",entry.getKey());row.addProperty("value",entry.getValue().getAsString());rows.add(row);
        });table.getItems().setAll(rows);
    }
    private static List<JsonObject> objects(JsonArray array) {List<JsonObject> rows=new ArrayList<>();array.forEach(row -> rows.add(row.getAsJsonObject()));return rows;}
    private static TableView<JsonObject> table(String...keys) {
        TableView<JsonObject> table=new TableView<>();table.setPlaceholder(new Label("No entries"));
        for(String key:keys){TableColumn<JsonObject,String> column=new TableColumn<>(key.substring(0,1).toUpperCase(Locale.ROOT)+key.substring(1));
            column.setCellValueFactory(row -> new SimpleStringProperty(string(row.getValue(),key)));column.setPrefWidth(key.equals("address")?140:240);table.getColumns().add(column);}
        return table;
    }
    private Tab tab(String title,Node node){Tab tab=new Tab(title,node);tab.setClosable(false);tabs.getTabs().add(tab);return tab;}
    private static String string(JsonObject object,String key){JsonElement value=object.get(key);return value==null || value.isJsonNull()?"":value.isJsonPrimitive()?value.getAsString():value.toString();}
    private static JsonObject command(String operation){JsonObject request=new JsonObject();request.addProperty("op",operation);return request;}
    private JsonElement call(String operation)throws Exception{return requireSession().request(operation,this::progress);}
    private JsonElement at(String operation,String target)throws Exception{JsonObject request=command(operation);request.addProperty("address",target);return requireSession().request(request,this::progress);}
    private GhidraSession requireSession(){if(session==null)throw new IllegalStateException("Open a file first.");return session;}
    private void progress(String message){if(!disposed)Platform.runLater(() -> {if(!disposed)status.setText(message);});}
    private <T> void execute(String message,Callable<T> action,Consumer<T> success) {
        if(disposed || busy.get())return;busy.set(true);cancelled.set(false);status.setText(message);
        worker.submit(() -> {
            runningThread=Thread.currentThread();
            try {
                T result=action.call();
                Platform.runLater(() -> {if(!disposed){busy.set(false);ready.set(session!=null && session.isAlive());success.accept(result);}});
            }catch(Exception error){
                Platform.runLater(() -> {if(!disposed){busy.set(false);ready.set(session!=null && session.isAlive());status.setText(Objects.toString(error.getMessage(),error.toString()));if(code.getText().equals("Decompiling…"))code.setText(status.getText());}});
            }finally{runningThread=null;Thread.interrupted();}
        });
    }
    private void cancel(){cancelled.set(true);GhidraSession current=session;if(current!=null)current.cancel();else{Thread thread=runningThread;if(thread!=null)thread.interrupt();}}
    @Override public void close() {
        if(disposed)return;disposed=true;cancel();code.close();assembly.close();pcode.close();
        worker.submit(() -> {try{if(session!=null)session.close();}catch(Exception error){System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Could not close Ghidra",error);}});
        worker.shutdown();
    }
}
