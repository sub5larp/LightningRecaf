package recafx.ui;

import com.google.gson.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.*;
import javafx.scene.paint.Color;
import java.util.*;
import java.util.function.Consumer;

/** Bounded graph rendering; decompiler and graph analysis remain in the Ghidra worker. */
final class GhidraFlowGraph extends Pane {
    GhidraFlowGraph(JsonObject graph,Consumer<String> navigate) {
        JsonArray nodes=graph.getAsJsonArray("nodes"),edges=graph.getAsJsonArray("edges");
        Map<String,double[]> positions=new LinkedHashMap<>();
        int count=Math.min(200,nodes.size());
        for(int i=0;i<count;i++) {
            JsonObject node=nodes.get(i).getAsJsonObject();String id=node.get("id").getAsString();
            positions.put(id,new double[]{40+(i%2)*340,45+(i/2)*135});
        }
        for(JsonElement element:edges) {
            JsonObject edge=element.getAsJsonObject();double[] from=positions.get(edge.get("from").getAsString()),to=positions.get(edge.get("to").getAsString());
            if(from==null || to==null)continue;
            double x1=from[0]+120,y1=from[1]+70,x2=to[0]+120,y2=to[1];
            CubicCurve line=new CubicCurve(x1,y1,x1,y1+30,x2,y2-30,x2,y2);line.setStroke(Color.web("#777777"));line.setStrokeWidth(1.5);line.setFill(null);
            Polygon arrow=new Polygon(x2,y2,x2-4,y2-8,x2+4,y2-8);arrow.setFill(Color.web("#999999"));
            Tooltip.install(line,new Tooltip(edge.get("type").getAsString()));getChildren().addAll(line,arrow);
        }
        for(int i=0;i<count;i++) {
            JsonObject node=nodes.get(i).getAsJsonObject();String id=node.get("id").getAsString();double[] position=positions.get(id);
            Label label=new Label(node.get("name").getAsString()+"\n"+id+" — "+node.get("end").getAsString());label.setWrapText(true);
            StackPane box=new StackPane(label);box.resizeRelocate(position[0],position[1],240,70);box.setMinSize(240,70);box.setPrefSize(240,70);
            box.setStyle("-fx-background-color: -color-bg-default; -fx-border-color: #555555; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 10;");
            box.setOnMouseClicked(e -> {if(e.getClickCount()==2)navigate.accept(id);});getChildren().add(box);
        }
        if(nodes.size()>count || graph.get("truncated").getAsBoolean()) {
            Label limit=new Label("Showing "+count+" blocks. Open the full Ghidra workbench for the complete graph.");limit.relocate(20,10);getChildren().add(limit);
        }
        setPrefSize(680,Math.max(200,90+Math.ceil(count/2.0)*135));
    }
}
