package recafx.ui;

import software.coley.recaf.ui.LanguageStylesheets;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.ui.control.richtext.syntax.RegexRule;
import software.coley.recaf.ui.control.richtext.syntax.RegexSyntaxHighlighter;
import java.util.*;

/** Uses the same editor, selections and palette classes as Java source. */
final class NativeCodeView extends Editor {
    NativeCodeView(boolean cSource) {
        getCodeArea().setEditable(false);
        String stylesheet=LanguageStylesheets.getLanguageStylesheet("java");
        if(stylesheet!=null)getStylesheets().add(stylesheet);
        List<RegexRule> rules=new ArrayList<>();
        rules.add(new RegexRule("BlockComment","/\\*[^*]*(?:\\*(?!/)[^*]*)*\\*/",List.of("comment-multi"),List.of(),"/*","*/"));
        rules.add(rule("LineComment","//[^\\n]*","comment-line"));
        rules.add(rule("String","\"(?:[^\"\\\\\\n]|\\\\.)*\"|'(?:[^'\\\\\\n]|\\\\.)*'","string"));
        rules.add(rule("Number","\\b(?:0[xX][0-9a-fA-F]+|[0-9]+(?:\\.[0-9]+)?)[uUlLfF]*\\b","constant"));
        if(cSource) {
            rules.add(rule("Keyword","\\b(?:auto|break|case|catch|class|const|continue|default|delete|do|else|enum|extern|for|goto|if|inline|namespace|new|register|return|sizeof|static|struct|switch|template|this|throw|typedef|union|using|virtual|volatile|while|__cdecl|__stdcall|__fastcall)\\b","keyword"));
            rules.add(rule("Type","\\b(?:void|bool|char|short|int|long|float|double|signed|unsigned|u?int[0-9]*|undefined[1248]?|size_t|wchar_t|true|false|NULL|nullptr)\\b","keyword"));
            rules.add(rule("Preprocessor","(?m)^\\s*#[^\\n]*","annotation"));
        } else {
            rules.add(rule("Opcode","\\b(?:[A-Z][A-Z_0-9]+|MOV|LEA|CALL|RET|JMP|PUSH|POP|ADD|SUB|XOR|CMP|TEST)\\b","keyword"));
            rules.add(rule("Register","(?i)\\b(?:[re]?(?:ax|bx|cx|dx|si|di|sp|bp)|r[0-9]+[dwb]?|xmm[0-9]+)\\b","annotation"));
        }
        setSyntaxHighlighter(new RegexSyntaxHighlighter(new RegexRule("Native","",List.of(),rules,null,null)));
    }
    void clear() { setText(""); }
    private static RegexRule rule(String name,String expression,String style) {
        return new RegexRule(name,expression,List.of(style),List.of(),null,null);
    }
}
