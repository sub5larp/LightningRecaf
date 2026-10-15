package recafx.ui;

import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import recafx.analysis.AnalysisService;
import recafx.deobf.*;
import software.coley.recaf.services.mapping.format.TinyV2Mappings;
import software.coley.recaf.ui.window.RecafScene;
import software.coley.recaf.ui.window.RecafStage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Reviewable evidence plus exportable name mappings; never exports analyzed input implicitly. */
public final class RecoveryReportWindow extends RecafStage {
    public RecoveryReportWindow(AutoDeobfuscator pipeline, AnalysisService analysis, ViewStateManager views) {
        setTitle("LightningRecaf · Recovery evidence");
        TextArea report = new TextArea(); report.setEditable(false); report.setWrapText(true);
        Runnable refresh = () -> {
            StringBuilder text = new StringBuilder(pipeline.getLastReport());
            text.append("\n\nClass indicators (heuristics, not proof of an obfuscator or malicious behavior)\n");
            analysis.getReport().classes().values().stream().filter(c -> c.obfuscated()).sorted(java.util.Comparator.comparing(c->c.className()))
                .forEach(c -> text.append(c.className()).append(": ").append(c.reasonSummary()).append('\n'));
            text.append("\nNetwork indicators: ").append(analysis.networkClassCount()).append(" classes. See Lightning > Network report.\n");
            report.setText(text.toString());
        };
        Button update = new Button("Refresh"); update.setOnAction(e -> refresh.run());
        Button copy = new Button("Copy"); copy.setOnAction(e -> {
            ClipboardContent content=new ClipboardContent();content.putString(report.getText());Clipboard.getSystemClipboard().setContent(content);
        });
        Button save = new Button("Save report..."); save.setOnAction(e -> save(report.getText(), "LightningRecaf-report.txt"));
        Button mapping = new Button("Save name mappings..."); mapping.setOnAction(e -> {
            var state=views.getState();
            if(state==null || state.getForwardMappings()==null || state.getForwardMappings().isEmpty()) {
                new Alert(Alert.AlertType.INFORMATION,"No normalized name mappings are available.",ButtonType.OK).show();return;
            }
            try {save(new TinyV2Mappings().exportText(state.getForwardMappings()),"LightningRecaf-names.tiny");}
            catch(Exception error) {new Alert(Alert.AlertType.ERROR,error.toString(),ButtonType.OK).show();}
        });
        Button coverage = new Button("Coverage & limits"); coverage.setOnAction(e -> {
            try(var stream=getClass().getResourceAsStream("/recafx/coverage.txt")) {
                report.setText(stream==null?"Coverage file is unavailable.":new String(stream.readAllBytes(),StandardCharsets.UTF_8));
            }catch(Exception error){report.setText(error.toString());}
        });
        ToolBar bar=new ToolBar(update,copy,save,mapping,coverage);
        BorderPane root=new BorderPane(report);root.setTop(bar);
        setScene(new RecafScene(root,900,620));refresh.run();
    }
    private void save(String text,String name) {
        FileChooser chooser=new FileChooser();chooser.setInitialFileName(name);chooser.setTitle("Save LightningRecaf evidence");
        java.io.File file=chooser.showSaveDialog(this);if(file==null)return;
        try {Files.writeString(file.toPath(),text,StandardCharsets.UTF_8);}
        catch(Exception error){new Alert(Alert.AlertType.ERROR,"Could not save: "+error.getMessage(),ButtonType.OK).show();}
    }
}
