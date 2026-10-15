package recafx.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import recafx.analysis.AnalysisService;
import recafx.analysis.NetworkAnalysis;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.navigation.ClassNavigable;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.List;

/**
 * Table of every network related finding in the workspace. Double click navigates to the class / method.
 */
public class NetworkReportPane extends BorderPane {
	public record Row(String className, String kind, String detail, String method) {}

	private final AnalysisService analysis;
	private final WorkspaceManager workspaceManager;
	private final Actions actions;
	private final ObservableList<Row> rows = FXCollections.observableArrayList();
	private final Label summary = new Label();
	private final Runnable analysisListener = () -> FxThreadUtil.run(this::refresh);

	public NetworkReportPane(AnalysisService analysis, WorkspaceManager workspaceManager, Actions actions) {
		this.analysis = analysis;
		this.workspaceManager = workspaceManager;
		this.actions = actions;

		TableView<Row> table = new TableView<>();
		TableColumn<Row, String> colClass = new TableColumn<>("Class");
		colClass.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().className().replace('/', '.')));
		colClass.setPrefWidth(320);
		TableColumn<Row, String> colKind = new TableColumn<>("Kind");
		colKind.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().kind()));
		colKind.setPrefWidth(170);
		TableColumn<Row, String> colDetail = new TableColumn<>("Detail");
		colDetail.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().detail()));
		colDetail.setPrefWidth(420);
		TableColumn<Row, String> colMethod = new TableColumn<>("Method");
		colMethod.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().method() == null ? "" : d.getValue().method()));
		colMethod.setPrefWidth(220);
		table.getColumns().addAll(List.of(colClass, colKind, colDetail, colMethod));
		table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

		TextField filter = new TextField();
		filter.setPromptText("Filter (class, kind, detail)...");
		FilteredList<Row> filtered = new FilteredList<>(rows, r -> true);
		filter.textProperty().addListener((ob, old, cur) -> {
			String needle = cur == null ? "" : cur.toLowerCase();
			filtered.setPredicate(r -> needle.isEmpty()
					|| r.className().toLowerCase().contains(needle)
					|| r.kind().toLowerCase().contains(needle)
					|| r.detail().toLowerCase().contains(needle)
					|| (r.method() != null && r.method().toLowerCase().contains(needle)));
		});
		SortedList<Row> sorted = new SortedList<>(filtered);
		sorted.comparatorProperty().bind(table.comparatorProperty());
		table.setItems(sorted);
		table.setRowFactory(tv -> {
			TableRow<Row> row = new TableRow<>();
			row.setOnMouseClicked(e -> {
				if (e.getClickCount() == 2 && !row.isEmpty()) open(row.getItem());
			});
			return row;
		});

		Button refresh = new Button("Refresh", new FontIconView(CarbonIcons.RENEW));
		refresh.setOnAction(e -> refresh());
		HBox top = new HBox(8, filter, refresh, summary);
		top.setPadding(new Insets(6));
		HBox.setHgrow(filter, Priority.ALWAYS);
		top.setStyle("-fx-alignment: center-left;");
		setTop(top);
		setCenter(table);
		analysis.addListener(analysisListener);
		refresh();
	}

	public void refresh() {
		List<Row> out = new ArrayList<>();
		for (NetworkAnalysis.ClassFindings findings : analysis.getAllNetwork())
			for (NetworkAnalysis.Hit hit : findings.hits())
				out.add(new Row(findings.className(), hit.kind().label, hit.detail(), hit.method()));
		out.sort((a, b) -> {
			int c = a.className().compareTo(b.className());
			return c != 0 ? c : a.kind().compareTo(b.kind());
		});
		rows.setAll(out);
		summary.setText(out.size() + " findings in " + analysis.networkClassCount() + " classes");
	}

	public void dispose() { analysis.removeListener(analysisListener); }
	private void open(Row row) {
		Workspace ws = workspaceManager.getCurrent();
		if (ws == null) return;
		ClassPathNode path = ws.findJvmClass(row.className());
		if (path == null) return;
		try {
			ClassNavigable nav = actions.gotoDeclaration(path);
			if (row.method() != null) {
				int paren = row.method().indexOf('(');
				String name = paren < 0 ? row.method() : row.method().substring(0, paren);
				String desc = paren < 0 ? null : row.method().substring(paren);
				var member = desc == null ? path.getValue().getFirstDeclaredMethodByName(name) : path.getValue().getDeclaredMethod(name, desc);
				if (member != null) nav.requestFocus(member);
			}
		} catch (Throwable ignored) {}
	}
}
