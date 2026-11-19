import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Cuadrícula pequeña de "bandas y modos trabajados" que va encima de la tabla, junto a TODOS / HF / CB.
 * Al escribir un indicativo en el contacto, o al seleccionar un contacto del log, marca en verde
 * las bandas y modos (PH = fonía, CW, DIG = digitales) en los que ya se ha hecho contacto con ese indicativo.
 * Solo lee la base de datos: no cambia nada.
 */
class PanelTrabajado extends JComponent {

    static final String[] BANDAS = {"160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "2m", "70cm", "CB"};
    static final String[] ETIQ_BANDAS = {"160", "80", "60", "40", "30", "20", "17", "15", "12", "10", "6", "2", "70", "CB"};
    static final String[] MODOS = {"PH", "CW", "DIG"};

    private static final int ANCHO_ETIQ = 34, CELDA_W = 34, CELDA_H = 15, HUECO = 3, ALTO_CABECERA = 16, MARGEN = 1;
    private static final Color VERDE = new Color(20, 200, 20);
    // Fonía: un color por tipo (si hay varios, la casilla se reparte en franjas)
    static final String[] TIPOS_FONIA = {"SSB", "AM", "FM", "DV"};
    static final String[] NOMBRES_FONIA = {"SSB", "AM", "FM", "Voz digital"};
    static final Color[] COLORES_FONIA = {VERDE, new Color(255, 150, 30), new Color(60, 150, 255), new Color(190, 110, 255)};
    private static final Color CELDA_VACIA = new Color(52, 60, 76);
    private static final Color BORDE_CELDA = new Color(125, 140, 165);

    private final LibroGuardiaUI app;
    private String indicativo = "";
    private Map<String, Integer> trabajados = new HashMap<>();   // "40m|PH" -> nº de contactos
    private final Timer espera;
    private String pendiente = "";
    private int peticion = 0;

    PanelTrabajado(LibroGuardiaUI app) {
        this.app = app;
        setOpaque(false);
        int w = MARGEN * 2 + ANCHO_ETIQ + BANDAS.length * (CELDA_W + HUECO);
        int h = MARGEN * 2 + ALTO_CABECERA + MODOS.length * (CELDA_H + HUECO);
        Dimension d = new Dimension(w, h);
        setPreferredSize(d);
        setMinimumSize(d);
        setMaximumSize(d);
        setToolTipText("");   // activa los avisos por casilla
        // Pequeña espera para no consultar la base de datos a cada letra que se escribe
        espera = new Timer(250, e -> buscarAhora(pendiente));
        espera.setRepeats(false);
    }

    /** Muestra lo trabajado con este indicativo (vacío = cuadrícula limpia). */
    void mostrar(String ind) {
        pendiente = ind == null ? "" : ind.trim().toUpperCase();
        espera.restart();
    }

    private void buscarAhora(String ind) {
        final int miPeticion = ++peticion;
        if (ind.isEmpty()) {
            indicativo = "";
            trabajados = new HashMap<>();
            repaint();
            return;
        }
        final boolean vistaRC = app.radioclub != null && app.radioclub.esVistaRadioclubSeleccionada();
        final String operador = LibroGuardiaUI.operadorActual == null ? "" : LibroGuardiaUI.operadorActual;
        new SwingWorker<Map<String, Integer>, Void>() {
            @Override protected Map<String, Integer> doInBackground() throws Exception {
                Map<String, Integer> r = new HashMap<>();
                String sql = vistaRC
                        ? "SELECT COALESCE(banda,''), COALESCE(modo,'') FROM qsos WHERE UPPER(indicativo) = ? AND radioclub = 1"
                        : "SELECT COALESCE(banda,''), COALESCE(modo,'') FROM qsos WHERE UPPER(indicativo) = ? AND operador = ?";
                try (Connection c = LibroGuardiaUI.DatabaseManager.conectar();
                     PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, ind);
                    if (!vistaRC) ps.setString(2, operador);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String b = normalizarBanda(rs.getString(1));
                            String m = grupoModo(rs.getString(2));
                            if (b == null || m == null) continue;
                            r.merge(b + "|" + m, 1, Integer::sum);
                            if (m.equals("PH")) r.merge(b + "|PH|" + tipoFonia(rs.getString(2)), 1, Integer::sum);
                        }
                    }
                }
                return r;
            }
            @Override protected void done() {
                if (miPeticion != peticion) return;   // ya se pidió otro indicativo después
                try { trabajados = get(); } catch (Exception ex) { trabajados = new HashMap<>(); }
                indicativo = ind;
                repaint();
            }
        }.execute();
    }

    /** "40m", "40", "40M" -> "40m"; "70cm" -> "70cm"; "CB" o "11m" -> "CB". Otra banda -> null. */
    static String normalizarBanda(String banda) {
        if (banda == null) return null;
        String b = banda.trim().toLowerCase();
        if (b.isEmpty()) return null;
        if (b.equals("cb") || b.equals("11m") || b.equals("11")) return "CB";
        if (!b.endsWith("m")) b = b + "m";
        for (String x : BANDAS) if (x.equalsIgnoreCase(b)) return x;
        return null;
    }

    /** Fonía (PH), CW o digitales (DIG). */
    static String grupoModo(String modo) {
        if (modo == null) return null;
        String m = modo.trim().toUpperCase();
        if (m.isEmpty()) return null;
        if (m.startsWith("CW")) return "CW";
        switch (m) {
            case "LSB": case "USB": case "SSB": case "AM": case "FM": case "PH": case "PHONE":
            case "DV": case "DMR": case "C4FM": case "DSTAR": case "DIGITALVOICE":
                return "PH";
            default:
                return "DIG";
        }
    }

    /** SSB (LSB/USB/SSB/PH), AM, FM o DV (voz digital: DMR, C4FM, D-STAR...). */
    static String tipoFonia(String modo) {
        String m = modo == null ? "" : modo.trim().toUpperCase();
        if (m.equals("AM")) return "AM";
        if (m.equals("FM")) return "FM";
        if (m.equals("DV") || m.equals("DMR") || m.equals("C4FM") || m.equals("DSTAR") || m.equals("DIGITALVOICE")) return "DV";
        return "SSB";
    }

    private Rectangle celda(int col, int fila) {
        int x = MARGEN + ANCHO_ETIQ + col * (CELDA_W + HUECO);
        int y = MARGEN + ALTO_CABECERA + fila * (CELDA_H + HUECO);
        return new Rectangle(x, y, CELDA_W, CELDA_H);
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Color texto = UIManager.getColor("Label.foreground");
        if (texto == null) texto = Color.LIGHT_GRAY;

        // Cabecera: bandas
        g.setFont(new Font("Arial", Font.BOLD, 11));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(texto);
        for (int i = 0; i < ETIQ_BANDAS.length; i++) {
            Rectangle r = celda(i, 0);
            String s = ETIQ_BANDAS[i];
            g.drawString(s, r.x + (CELDA_W - fm.stringWidth(s)) / 2, MARGEN + ALTO_CABECERA - 4);
        }
        // Filas: PH / CW / DIG
        g.setFont(new Font("Arial", Font.BOLD, 12));
        fm = g.getFontMetrics();
        for (int f = 0; f < MODOS.length; f++) {
            Rectangle r0 = celda(0, f);
            g.setColor(texto);
            g.drawString(MODOS[f], MARGEN + 1, r0.y + (CELDA_H + fm.getAscent()) / 2 - 1);
            for (int c = 0; c < BANDAS.length; c++) {
                Rectangle r = celda(c, f);
                boolean hecho = trabajados.containsKey(BANDAS[c] + "|" + MODOS[f]);
                g.setColor(hecho ? VERDE : CELDA_VACIA);
                g.fillRect(r.x, r.y, r.width, r.height);
                if (hecho && MODOS[f].equals("PH")) {
                    java.util.List<Color> cs = new java.util.ArrayList<>();
                    for (int t = 0; t < TIPOS_FONIA.length; t++)
                        if (trabajados.containsKey(BANDAS[c] + "|PH|" + TIPOS_FONIA[t])) cs.add(COLORES_FONIA[t]);
                    for (int k = 0; k < cs.size(); k++) {
                        int x0 = r.x + r.width * k / cs.size(), x1 = r.x + r.width * (k + 1) / cs.size();
                        g.setColor(cs.get(k));
                        g.fillRect(x0, r.y, x1 - x0, r.height);
                    }
                }
                g.setColor(hecho ? new Color(20, 30, 40) : BORDE_CELDA);
                g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
            }
        }
        g.dispose();
    }

    @Override public String getToolTipText(MouseEvent e) {
        if (indicativo.isEmpty())
            return "Bandas y modos trabajados: escribe un indicativo o selecciona un contacto del log";
        for (int f = 0; f < MODOS.length; f++)
            for (int c = 0; c < BANDAS.length; c++)
                if (celda(c, f).contains(e.getPoint())) {
                    Integer n = trabajados.get(BANDAS[c] + "|" + MODOS[f]);
                    String modo = MODOS[f].equals("PH") ? "Fonía" : MODOS[f].equals("DIG") ? "Digitales" : "CW";
                    String detalle = "";
                    if (n != null && MODOS[f].equals("PH")) {
                        StringBuilder sb = new StringBuilder();
                        for (int t = 0; t < TIPOS_FONIA.length; t++) {
                            Integer k = trabajados.get(BANDAS[c] + "|PH|" + TIPOS_FONIA[t]);
                            if (k != null) sb.append(sb.length() == 0 ? "  (" : ", ").append(NOMBRES_FONIA[t]).append(": ").append(k);
                        }
                        if (sb.length() > 0) detalle = sb.append(")").toString();
                    }
                    return indicativo + "  ·  " + BANDAS[c] + "  ·  " + modo + ":  "
                            + (n == null ? "sin contacto" : n + (n == 1 ? " contacto" : " contactos")) + detalle;
                }
        return trabajados.isEmpty() ? indicativo + ": sin contactos en este log"
                : "<html>" + indicativo + ": casillas de color = bandas y modos ya trabajados.<br>"
                + "Fonía (PH): <font color='#14c814'>■</font> SSB · <font color='#ff961e'>■</font> AM · "
                + "<font color='#3c96ff'>■</font> FM · <font color='#be6eff'>■</font> voz digital</html>";
    }
}
