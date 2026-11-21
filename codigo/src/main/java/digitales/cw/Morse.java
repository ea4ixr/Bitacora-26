package digitales.cw;

import java.util.HashMap;
import java.util.Map;

/** Tabla de Morse (internacional) en los dos sentidos. Las señales de procedimiento van entre < >. */
public final class Morse {
    private Morse() { }

    private static final String[][] T = {
            {"A", ".-"}, {"B", "-..."}, {"C", "-.-."}, {"D", "-.."}, {"E", "."}, {"F", "..-."}, {"G", "--."}, {"H", "...."},
            {"I", ".."}, {"J", ".---"}, {"K", "-.-"}, {"L", ".-.."}, {"M", "--"}, {"N", "-."}, {"O", "---"}, {"P", ".--."},
            {"Q", "--.-"}, {"R", ".-."}, {"S", "..."}, {"T", "-"}, {"U", "..-"}, {"V", "...-"}, {"W", ".--"}, {"X", "-..-"},
            {"Y", "-.--"}, {"Z", "--.."},
            {"0", "-----"}, {"1", ".----"}, {"2", "..---"}, {"3", "...--"}, {"4", "....-"}, {"5", "....."}, {"6", "-...."},
            {"7", "--..."}, {"8", "---.."}, {"9", "----."},
            {".", ".-.-.-"}, {",", "--..--"}, {"?", "..--.."}, {"/", "-..-."}, {"=", "-...-"}, {"+", ".-.-."}, {"-", "-....-"},
            {"(", "-.--."}, {")", "-.--.-"}, {"'", ".----."}, {":", "---..."}, {"@", ".--.-."}, {"!", "-.-.--"}, {"\"", ".-..-."},
            {"<AR>", ".-.-."}, {"<SK>", "...-.-"}, {"<KN>", "-.--."}, {"<BT>", "-...-"}, {"<AS>", ".-..."}, {"<HH>", "........"},
            {"Ñ", "--.--"},
    };
    private static final Map<String, String> A_MORSE = new HashMap<>(), A_TEXTO = new HashMap<>();
    static {
        for (String[] t : T) {
            A_MORSE.putIfAbsent(t[0], t[1]);
            A_TEXTO.putIfAbsent(t[1], t[0]);
        }
        // Al recibir se ven como los usa el radioaficionado
        A_TEXTO.put(".-.-.", "<AR>");
        A_TEXTO.put("-.--.", "<KN>");
        A_TEXTO.put("-...-", "=");
    }

    /** Texto de un código (puntos y rayas), o null si no existe. */
    public static String texto(String codigo) { return A_TEXTO.get(codigo); }

    /** Código de un carácter o señal de procedimiento (<SK>...), o null. */
    public static String codigo(String c) { return A_MORSE.get(c.toUpperCase()); }
}
