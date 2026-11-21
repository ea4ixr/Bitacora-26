import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «Próximas actividades»: las actividades que anuncian los usuarios (día, hora, frecuencias, indicativo y muestra del diploma).
 * - La lista la ve todo el mundo (se pide al servidor de bitacora26.es); con cuenta, las tuyas se pueden cambiar o quitar.
 * - «Ir a la frecuencia» solo rellena la frecuencia del contacto: el CAT sigue siendo de solo lectura.
 * - «Avisarme» saca un aviso 15 minutos antes.
 * - El formulario para anunciar (PanelAnuncio) se usa también dentro de «Nueva actividad».
 * Las horas se guardan y se mandan siempre en UTC.
 */
final class ProximasActividades {

    private static final String BASE = System.getProperty("bitacora26.cuentas", "https://bitacora26.es/api/cuentas");
    private static final String WEB = BASE.replaceFirst("/api/cuentas$", "");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Color FONDO = new Color(0x121c2e), CAJA = new Color(0x0f1828), LINEA = new Color(0x243450), TEXTO = new Color(0xdfe7f3),
            GRIS = new Color(0x8796ad), AMBAR = new Color(0xf5b324), VERDE = new Color(0x4cd07d), ROJO = new Color(0xe04848), AZUL = new Color(0x5aa9ff);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm"), DM = DateTimeFormatter.ofPattern("dd/MM");

    /** Una actividad tal como la da el servidor (horas en segundos UTC). */
    record Actividad(String aid, String nombre, String ind, long inicio, long fin, String frec, String info, String club,
                     String organiza, boolean spot, boolean aire, String img, boolean mia) { }

    private static volatile List<Actividad> lista = List.of();
    private static JDialog ventana;
    private static javax.swing.Timer avisos;
    private static final Map<String, ImageIcon> miniaturas = new HashMap<>();

    private ProximasActividades() { }

    // ------------------------------------------------------------------ servidor
    private static String cuenta(LibroGuardiaUI app) { return app.cluster.cuentaParaVer(); }

    /** Lee la lista (con cuenta, para saber cuáles son mías). Devuelve null si ha ido bien, o el motivo. No llamar desde la pantalla. */
    static String leer(LibroGuardiaUI app) {
        try {
            String cta = cuenta(app), cuerpo;
            if (!cta.isEmpty()) {
                String[] r = app.cuentaB26.firmadoB26(cta, "act_ver", "{}");
                if (!"200".equals(r[0])) return "0".equals(r[0]) ? "sin conexión con Bitácora 26" : CuentaBitacora.errorJson(r);
                cuerpo = r[1];
            } else {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + "/actividades")).timeout(Duration.ofSeconds(15)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return "respuesta " + r.statusCode();
                cuerpo = r.body();
            }
            lista = deJson(cuerpo);
            return null;
        } catch (Exception e) {
            return "sin conexión con Bitácora 26";
        }
    }

    private static boolean verdad(String o, String k) { return Pattern.compile("\"" + k + "\"\\s*:\\s*true").matcher(o).find(); }

    private static List<Actividad> deJson(String cuerpo) {
        List<Actividad> l = new ArrayList<>();
        int ini = cuerpo.indexOf("\"actividades\"");
        Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(ini < 0 ? "" : cuerpo.substring(ini));
        while (m.find()) {
            String o = m.group();
            l.add(new Actividad(LibroGuardiaUI.campoJson(o, "aid"), LibroGuardiaUI.campoJson(o, "nombre"), LibroGuardiaUI.campoJson(o, "ind"),
                    LibroGuardiaUI.numeroJson(o, "inicio"), LibroGuardiaUI.numeroJson(o, "fin"), LibroGuardiaUI.campoJson(o, "frec"),
                    LibroGuardiaUI.campoJson(o, "info"), LibroGuardiaUI.campoJson(o, "club"), LibroGuardiaUI.campoJson(o, "organiza"),
                    verdad(o, "spot"), verdad(o, "aire"), LibroGuardiaUI.campoJson(o, "img"), verdad(o, "mia")));
        }
        l.sort(Comparator.comparing((Actividad a) -> !a.aire()).thenComparingLong(Actividad::inicio));
        return l;
    }

    /** Manda la actividad (nueva si aid está vacío). Devuelve {null, aid} si ha ido bien, o {motivo, ""}. No llamar desde la pantalla. */
    static String[] guardar(LibroGuardiaUI app, String json) {
        String cta = cuenta(app);
        if (cta.isEmpty()) return new String[]{"necesitas una cuenta de Bitácora 26 para anunciar actividades", ""};
        String[] r = app.cuentaB26.firmadoB26(cta, "act_guardar", json);
        if ("0".equals(r[0])) r = app.cuentaB26.firmadoB26(cta, "act_guardar", json);
        if (!"200".equals(r[0])) return new String[]{"0".equals(r[0]) ? "sin conexión con Bitácora 26" : CuentaBitacora.errorJson(r), ""};
        lista = deJson(r[1]);
        return new String[]{null, LibroGuardiaUI.campoJson(r[1], "aid")};
    }

    static String quitar(LibroGuardiaUI app, String aid) {
        String cta = cuenta(app);
        if (cta.isEmpty()) return "necesitas una cuenta de Bitácora 26";
        String[] r = app.cuentaB26.firmadoB26(cta, "act_quitar", "{\"aid\":\"" + LibroGuardiaUI.jsonTexto(aid) + "\"}");
        if (!"200".equals(r[0])) return "0".equals(r[0]) ? "sin conexión con Bitácora 26" : CuentaBitacora.errorJson(r);
        lista = deJson(r[1]);
        return null;
    }

    /** De «7.140 SSB · 14.250» saca 7140000 Hz (MHz o kHz). 0 si no hay. */
    static long primeraFrecuencia(String frec) {
        Matcher m = Pattern.compile("(\\d{1,6}(?:[.,]\\d{1,4})?)\\s*(MHZ|KHZ)?").matcher(frec == null ? "" : frec.toUpperCase(Locale.ROOT));
        if (!m.find()) return 0;
        double v = Double.parseDouble(m.group(1).replace(',', '.'));
        boolean khz = "KHZ".equals(m.group(2)) || (m.group(2) == null && v >= 1000);
        return Math.round(khz ? v * 1000 : v * 1_000_000);
    }

    /** Imagen (cualquier formato) reducida a 640x460 como mucho, en JPEG de unos 40 KB, en base64. */
    static String imagenReducida(File f) throws Exception {
        BufferedImage im = ImageIO.read(f);
        if (im == null) throw new Exception("no es una imagen");
        double k = Math.min(1.0, Math.min(640.0 / im.getWidth(), 460.0 / im.getHeight()));
        int w = Math.max(1, (int) Math.round(im.getWidth() * k)), h = Math.max(1, (int) Math.round(im.getHeight() * k));
        BufferedImage r = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = r.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(im, 0, 0, w, h, null);
        g.dispose();
        byte[] b = null;
        for (float q = 0.8f; q >= 0.3f; q -= 0.1f) {
            ImageWriter wr = ImageIO.getImageWritersByFormatName("jpg").next();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream os = new MemoryCacheImageOutputStream(bo)) {
                wr.setOutput(os);
                ImageWriteParam p = wr.getDefaultWriteParam();
                p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                p.setCompressionQuality(q);
                wr.write(null, new IIOImage(r, null, null), p);
            } finally { wr.dispose(); }
            b = bo.toByteArray();
            if (b.length <= 60_000) break;
        }
        return Base64.getEncoder().encodeToString(b);
    }

    // ------------------------------------------------------------------ avisos («Avisarme»)
    private static Set<String> avisar() {
        Set<String> s = new LinkedHashSet<>();
        for (String x : LibroGuardiaUI.ConfigApp.get("ACT_AVISAR", "").split(",")) if (!x.isBlank()) s.add(x.trim());
        return s;
    }

    private static void guardarAvisar(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        while (l.size() > 50) l.remove(0);
        LibroGuardiaUI.ConfigApp.put("ACT_AVISAR", String.join(",", l));
    }

    /** Se llama una vez al arrancar: cada minuto mira si hay que avisar de alguna actividad (15 minutos antes). */
    static void arrancarAvisos(LibroGuardiaUI app) {
        if (avisos != null) return;
        final long[] ultimaLectura = {0};
        avisos = new javax.swing.Timer(60_000, e -> {
            Set<String> s = avisar();
            if (s.isEmpty()) return;
            new Thread(() -> {
                long ahora = Instant.now().getEpochSecond();
                if (ahora - ultimaLectura[0] > 600) { leer(app); ultimaLectura[0] = ahora; }
                Set<String> avisados = new HashSet<>(Arrays.asList(LibroGuardiaUI.ConfigApp.get("ACT_AVISADOS", "").split(",")));
                for (Actividad a : lista) {
                    if (!s.contains(a.aid()) || avisados.contains(a.aid())) continue;
                    if (a.inicio() - ahora <= 15 * 60 && a.fin() > ahora) {
                        avisados.add(a.aid());
                        List<String> l = new ArrayList<>(avisados);
                        while (l.size() > 50) l.remove(0);
                        LibroGuardiaUI.ConfigApp.put("ACT_AVISADOS", String.join(",", l).replaceFirst("^,", ""));
                        SwingUtilities.invokeLater(() -> avisoEmergente(app, a));
                    }
                }
            }, "avisos-actividades").start();
        });
        avisos.setInitialDelay(20_000);
        avisos.start();
    }

    private static void avisoEmergente(LibroGuardiaUI app, Actividad a) {
        Toolkit.getDefaultToolkit().beep();
        long min = Math.max(0, (a.inicio() - Instant.now().getEpochSecond()) / 60);
        JOptionPane p = new JOptionPane("<html><b>" + esc(a.nombre()) + "</b> · " + esc(a.ind()) + "<br>"
                + (min == 0 ? "Está empezando ahora" : "Empieza dentro de " + min + " minutos") + " · " + esc(a.frec()) + "</html>", JOptionPane.INFORMATION_MESSAGE);
        JDialog d = p.createDialog(app, "Próximas actividades · aviso");
        d.setModal(false);
        d.setAlwaysOnTop(true);
        d.setVisible(true);
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    // ------------------------------------------------------------------ ventana de la lista
    /** Botón para la ventana Cluster. */
    static JButton boton(LibroGuardiaUI app) {
        JButton b = AprenderCw.botonPrin("Próximas actividades");
        b.setFont(new Font("Dialog", Font.BOLD, 12));
        b.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        b.setToolTipText("Actividades anunciadas por los usuarios de Bitácora 26 (días, horas, frecuencias y diploma)");
        b.addActionListener(e -> abrir(app, SwingUtilities.getWindowAncestor(b)));
        return b;
    }

    static void abrir(LibroGuardiaUI app, Window duenio) {
        if (ventana != null && ventana.isDisplayable()) { ventana.toFront(); refrescar(app); return; }
        JDialog d = new JDialog(duenio == null ? app : duenio, "Próximas actividades · Bitácora 26", Dialog.ModalityType.MODELESS);
        d.setIconImages(app.getIconImages());
        JPanel raiz = new JPanel(new BorderLayout(0, 10));
        raiz.setBackground(FONDO);
        raiz.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        JPanel filas = new JPanel();
        filas.setLayout(new BoxLayout(filas, BoxLayout.Y_AXIS));
        filas.setOpaque(false);
        JScrollPane sc = new JScrollPane(filas, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sc.setBorder(null);
        sc.getViewport().setBackground(FONDO);
        sc.getVerticalScrollBar().setUnitIncrement(18);
        JLabel estado = new JLabel("Leyendo…");
        estado.setForeground(GRIS);
        JButton bNueva = AprenderCw.botonPrin("Anunciar una actividad…");
        bNueva.addActionListener(e -> formularioSuelto(app, d, null));
        JButton bLeer = AprenderCw.boton("Actualizar");
        bLeer.addActionListener(e -> refrescar(app));
        JPanel pie = new JPanel(new BorderLayout());
        pie.setOpaque(false);
        JPanel bts = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bts.setOpaque(false);
        bts.add(bNueva);
        bts.add(bLeer);
        pie.add(bts, BorderLayout.WEST);
        pie.add(estado, BorderLayout.EAST);
        JLabel tit = new JLabel("Próximas actividades · horas en UTC");
        tit.setFont(new Font("Dialog", Font.BOLD, 15));
        tit.setForeground(AMBAR);
        raiz.add(tit, BorderLayout.NORTH);
        raiz.add(sc, BorderLayout.CENTER);
        raiz.add(pie, BorderLayout.SOUTH);
        d.setContentPane(raiz);
        d.getRootPane().putClientProperty("filas", filas);
        d.getRootPane().putClientProperty("estado", estado);
        d.setSize(1040, 640);
        d.setLocationRelativeTo(duenio == null ? app : duenio);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        ventana = d;
        d.setVisible(true);
        refrescar(app);
    }

    private static void refrescar(LibroGuardiaUI app) {
        JDialog d = ventana;
        if (d == null) return;
        JLabel estado = (JLabel) d.getRootPane().getClientProperty("estado");
        estado.setText("Leyendo…");
        new Thread(() -> {
            String err = leer(app);
            SwingUtilities.invokeLater(() -> {
                pintar(app);
                estado.setText(err == null ? lista.size() + (lista.size() == 1 ? " actividad" : " actividades") : "No se ha podido leer: " + err);
            });
        }, "proximas-actividades").start();
    }

    private static void pintar(LibroGuardiaUI app) {
        JDialog d = ventana;
        if (d == null) return;
        JPanel filas = (JPanel) d.getRootPane().getClientProperty("filas");
        filas.removeAll();
        if (lista.isEmpty()) {
            JLabel v = new JLabel("<html><i>No hay actividades anunciadas. ¿Organizas una? Anúnciala con «Anunciar una actividad…» o al crearla en «Nueva actividad».</i></html>");
            v.setForeground(GRIS);
            v.setAlignmentX(0f);
            filas.add(v);
        }
        for (Actividad a : lista) { JComponent f = fila(app, a); f.setAlignmentX(0f); filas.add(f); filas.add(Box.createVerticalStrut(8)); }
        filas.revalidate();
        filas.repaint();
    }

    private static JLabel etiqueta(String t, Color fondo, Color letra) {
        JLabel l = new JLabel(" " + t + " ");
        l.setOpaque(true);
        l.setBackground(fondo);
        l.setForeground(letra);
        l.setFont(new Font("Dialog", Font.BOLD, 10));
        return l;
    }

    private static JComponent fila(LibroGuardiaUI app, Actividad a) {
        JPanel p = new JPanel(new GridBagLayout()) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(a.aire() ? new Color(0x11291b) : CAJA);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g.setColor(a.aire() ? VERDE : LINEA);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g.dispose();
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
        ZonedDateTime ini = Instant.ofEpochSecond(a.inicio()).atZone(ZoneOffset.UTC), fin = Instant.ofEpochSecond(a.fin()).atZone(ZoneOffset.UTC);
        LocalDate hoy = LocalDate.now(ZoneOffset.UTC);
        // Cuándo
        JPanel cuando = new JPanel();
        cuando.setLayout(new BoxLayout(cuando, BoxLayout.Y_AXIS));
        cuando.setOpaque(false);
        if (a.aire()) cuando.add(etiqueta("EN EL AIRE", ROJO, Color.WHITE));
        else if (ini.toLocalDate().equals(hoy)) cuando.add(etiqueta("HOY", AMBAR, new Color(0x1a1200)));
        else if (ini.toLocalDate().equals(hoy.plusDays(1))) cuando.add(etiqueta("MAÑANA", AMBAR, new Color(0x1a1200)));
        boolean varios = !fin.minusSeconds(1).toLocalDate().equals(ini.toLocalDate());
        JLabel lc = new JLabel("<html>" + (ini.toLocalDate().equals(hoy) ? "hoy" : ini.format(DM)) + " " + ini.format(HM) + "–" + fin.format(HM)
                + (varios ? "<br><font color='#8796ad'>hasta el " + fin.format(DM) + "</font>" : "") + "</html>");
        lc.setForeground(TEXTO);
        cuando.add(lc);
        // Actividad, indicativo, frecuencias, organiza
        JLabel lt = new JLabel("<html><b style='font-size:12px'>" + esc(a.nombre()) + "</b> · <font color='#f5b324' face='Monospaced'><b>" + esc(a.ind()) + "</b></font>"
                + (a.club().isEmpty() ? "" : " <font color='#5aa9ff'>RADIOCLUB</font>")
                + "<br><font color='#dfe7f3'>" + esc(a.frec()) + "</font>"
                + "<br><font color='#8796ad'>Organiza " + esc(a.organiza()) + (a.info().isEmpty() ? "" : " · " + esc(a.info())) + "</font></html>");
        lt.setForeground(TEXTO);
        // Diploma
        JLabel img = new JLabel(a.img().isEmpty() ? "sin diploma" : "…", SwingConstants.CENTER);
        img.setPreferredSize(new Dimension(150, 106));
        img.setMinimumSize(new Dimension(150, 106));
        img.setForeground(GRIS);
        img.setFont(new Font("Dialog", Font.PLAIN, 11));
        img.setBorder(BorderFactory.createLineBorder(LINEA));
        if (!a.img().isEmpty()) cargarMiniatura(a, img);
        // Botones
        JPanel bts = new JPanel(new GridLayout(0, 1, 0, 4));
        bts.setOpaque(false);
        long hz = primeraFrecuencia(a.frec());
        if (hz > 0) {
            JButton ir = a.aire() ? AprenderCw.botonPrin("Ir a la frecuencia") : AprenderCw.boton("Ir a la frecuencia");
            ir.setToolTipText("Rellena la frecuencia y el indicativo en el contacto (no se manda nada a la emisora)");
            ir.addActionListener(e -> irA(app, a, hz));
            bts.add(ir);
        }
        boolean av = avisar().contains(a.aid());
        JButton bAv = AprenderCw.boton(av ? "Avisándome ✓" : "Avisarme");
        bAv.setToolTipText("Te avisa Bitácora 26 15 minutos antes de que empiece");
        bAv.addActionListener(e -> {
            Set<String> s = avisar();
            if (!s.remove(a.aid())) s.add(a.aid());
            guardarAvisar(s);
            bAv.setText(s.contains(a.aid()) ? "Avisándome ✓" : "Avisarme");
        });
        if (!a.aire()) bts.add(bAv);
        if (a.mia()) {
            JButton c = AprenderCw.boton("Cambiar");
            c.addActionListener(e -> formularioSuelto(app, ventana, a));
            JButton q = AprenderCw.boton("Quitar");
            q.addActionListener(e -> {
                if (JOptionPane.showConfirmDialog(ventana, "¿Quitar «" + a.nombre() + "» de Próximas actividades?", "Quitar", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
                new Thread(() -> {
                    String err = quitar(app, a.aid());
                    SwingUtilities.invokeLater(() -> {
                        if (err != null) JOptionPane.showMessageDialog(ventana, "No se ha podido quitar: " + err, "Próximas actividades", JOptionPane.WARNING_MESSAGE);
                        pintar(app);
                    });
                }).start();
            });
            bts.add(c);
            bts.add(q);
        }
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(0, 0, 0, 14);
        g.gridx = 0; g.weightx = 0;
        cuando.setPreferredSize(new Dimension(120, cuando.getPreferredSize().height));
        p.add(cuando, g);
        g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
        p.add(lt, g);
        g.gridx = 2; g.weightx = 0; g.fill = GridBagConstraints.NONE;
        p.add(img, g);
        g.gridx = 3; g.insets = new Insets(0, 0, 0, 0);
        bts.setPreferredSize(new Dimension(150, bts.getPreferredSize().height));
        p.add(bts, g);
        return p;
    }

    private static void cargarMiniatura(Actividad a, JLabel l) {
        ImageIcon ya = miniaturas.get(a.img());
        if (ya != null) { ponerMiniatura(l, ya, a); return; }
        new Thread(() -> {
            try {
                HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(WEB + a.img())).timeout(Duration.ofSeconds(15)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                BufferedImage im = r.statusCode() == 200 ? ImageIO.read(new java.io.ByteArrayInputStream(r.body())) : null;
                if (im == null) { SwingUtilities.invokeLater(() -> l.setText("sin diploma")); return; }
                ImageIcon ic = new ImageIcon(im);
                synchronized (miniaturas) { miniaturas.put(a.img(), ic); }
                SwingUtilities.invokeLater(() -> ponerMiniatura(l, ic, a));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> l.setText("sin diploma"));
            }
        }, "miniatura-actividad").start();
    }

    private static void ponerMiniatura(JLabel l, ImageIcon grande, Actividad a) {
        Image im = grande.getImage();
        double k = Math.min(148.0 / grande.getIconWidth(), 104.0 / grande.getIconHeight());
        l.setText("");
        l.setIcon(new ImageIcon(im.getScaledInstance((int) (grande.getIconWidth() * k), (int) (grande.getIconHeight() * k), Image.SCALE_SMOOTH)));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.setToolTipText("Pincha para verla en grande");
        for (MouseListener m : l.getMouseListeners()) l.removeMouseListener(m);
        l.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                JDialog z = new JDialog(ventana, a.nombre(), Dialog.ModalityType.MODELESS);
                JLabel g = new JLabel(grande);
                g.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
                z.setContentPane(g);
                z.getContentPane().setBackground(FONDO);
                z.pack();
                z.setLocationRelativeTo(ventana);
                z.setVisible(true);
            }
        });
    }

    /** Rellena el contacto con el indicativo y la frecuencia de la actividad (sin tocar la emisora). */
    private static void irA(LibroGuardiaUI app, Actividad a, long hz) {
        String f = String.format(Locale.GERMANY, "%,d", hz);
        app.cat.frecuenciaPorPrograma = true;
        try {
            app.txtFreqTx.setText(f);
            app.txtFreqRx.setText(f);
        } finally { app.cat.frecuenciaPorPrograma = false; }
        String banda = app.normalizarOpcion(ClustersDX.banda(hz), app.BANDAS);
        if (app.cat.comboContiene(app.cbBanda, banda)) app.cbBanda.setSelectedItem(banda);
        Matcher m = Pattern.compile("\\b(SSB|LSB|USB|CW|AM|FM|FT8|FT4|RTTY)\\b").matcher(a.frec().toUpperCase(Locale.ROOT));
        if (m.find()) {
            String modo = app.normalizarOpcion(m.group(1), app.MODOS);
            if (app.cat.comboContiene(app.cbModo, modo)) app.cbModo.setSelectedItem(modo);
        }
        app.txtIndicativo.setText(a.ind());
        app.cat.refrescarFrontal();
        app.lblEstado.setText("Próximas actividades: " + a.nombre() + " · " + a.ind() + " · " + f + " Hz · la emisora no se toca (el CAT es solo lectura)");
        app.toFront();
    }

    // ------------------------------------------------------------------ formulario
    /** Formulario suelto (desde la lista): nueva actividad para anunciar, o cambiar una mía. */
    static void formularioSuelto(LibroGuardiaUI app, Window duenio, Actividad a) {
        JDialog d = new JDialog(duenio, a == null ? "Anunciar una actividad" : "Cambiar la actividad", Dialog.ModalityType.APPLICATION_MODAL);
        JTextField nombre = new JTextField(28);
        if (a != null) nombre.setText(a.nombre());
        PanelAnuncio pa = new PanelAnuncio(app, false);
        if (a != null) pa.cargar(a);
        JPanel raiz = new JPanel(new BorderLayout(0, 10));
        raiz.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        JPanel fn = new JPanel(new BorderLayout(10, 0));
        fn.setOpaque(false);
        JLabel ln = new JLabel("Nombre:");
        ln.setFont(ln.getFont().deriveFont(Font.BOLD, 13f));
        fn.add(ln, BorderLayout.WEST);
        fn.add(nombre, BorderLayout.CENTER);
        raiz.add(fn, BorderLayout.NORTH);
        raiz.add(pa, BorderLayout.CENTER);
        JButton ok = new JButton(a == null ? "Publicar" : "Guardar cambios");
        ok.setFont(ok.getFont().deriveFont(Font.BOLD, 14f));
        ok.setBackground(AMBAR);
        ok.setForeground(new Color(27, 20, 4));
        JButton can = new JButton("Cancelar");
        can.addActionListener(e -> d.dispose());
        ok.addActionListener(e -> {
            String json = pa.json(nombre.getText().trim(), a == null ? "" : a.aid(), "");
            if (json.startsWith("!")) { JOptionPane.showMessageDialog(d, json.substring(1), "Aviso", JOptionPane.WARNING_MESSAGE); return; }
            ok.setEnabled(false);
            new Thread(() -> {
                String[] r = guardar(app, json);
                SwingUtilities.invokeLater(() -> {
                    ok.setEnabled(true);
                    if (r[0] != null) { JOptionPane.showMessageDialog(d, "No se ha podido publicar: " + r[0], "Próximas actividades", JOptionPane.WARNING_MESSAGE); return; }
                    d.dispose();
                    pintar(app);
                });
            }).start();
        });
        JPanel b = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        b.setOpaque(false);
        b.add(ok);
        b.add(can);
        raiz.add(b, BorderLayout.SOUTH);
        d.setContentPane(raiz);
        d.pack();
        d.setLocationRelativeTo(duenio);
        d.setVisible(true);
    }

    /**
     * Recuadro «Anunciar en Próximas actividades»: calendario, horas (UTC o local), varios días, frecuencias,
     * indicativo al aire, comentario, spot automático e imagen del diploma.
     */
    static final class PanelAnuncio extends JPanel {
        final JCheckBox chkAnunciar = new JCheckBox("Anunciar en «Próximas actividades» (app y web)");
        private final Calendario cal = new Calendario();
        private final JTextField desde = new JTextField("09:00", 5), hasta = new JTextField("14:00", 5);
        private final JComboBox<String> zona = new JComboBox<>(new String[]{"UTC", "LOCAL"});
        private final JCheckBox chkVarios = new JCheckBox("Varios días: hasta el");
        private final JTextField diaFin = new JTextField(10);
        private final JTextField frec = new JTextField(26), ind = new JTextField(12);
        private final JTextArea info = new JTextArea(2, 30);
        private final JCheckBox chkSpot = new JCheckBox("Al empezar, mandar un spot automático al cluster de Bitácora 26", true);
        private final JButton bImg = new JButton("Imagen del diploma…");
        private final JLabel lImg = new JLabel("opcional · se reduce sola a unos 40 KB");
        private String imgB64 = "";
        private boolean quitarImg = false;
        private final JPanel cuerpo = new JPanel(new GridBagLayout());

        PanelAnuncio(LibroGuardiaUI app, boolean conCasilla) {
            super(new BorderLayout(0, 6));
            setOpaque(true);
            setBackground(new Color(0x141a12));
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createDashedBorder(AMBAR, 2f, 6f, 4f, true),
                    BorderFactory.createEmptyBorder(8, 10, 10, 10)));
            String op = app.operadorActual == null ? "" : app.operadorActual.trim().toUpperCase(Locale.ROOT);
            ind.setText(op);
            chkAnunciar.setOpaque(false);
            chkAnunciar.setFont(chkAnunciar.getFont().deriveFont(Font.BOLD, 13f));
            chkAnunciar.setSelected(!conCasilla);
            chkAnunciar.setVisible(conCasilla);
            cuerpo.setOpaque(false);
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = 0; g.anchor = GridBagConstraints.WEST; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
            g.insets = new Insets(3, 0, 3, 0);
            cuerpo.add(cal, g);
            g.gridy++;
            cuerpo.add(fila(new JLabel("Desde"), desde, new JLabel("hasta"), hasta, zona), g);
            g.gridy++;
            diaFin.setEnabled(false);
            diaFin.setToolTipText("dd/mm/aaaa");
            chkVarios.setOpaque(false);
            chkVarios.addActionListener(e -> diaFin.setEnabled(chkVarios.isSelected()));
            cuerpo.add(fila(chkVarios, diaFin), g);
            g.gridy++;
            cuerpo.add(fila(new JLabel("Frecuencias"), frec), g);
            frec.setToolTipText("Por ejemplo: 7.140 SSB · 14.250 SSB · 7.020 CW");
            g.gridy++;
            cuerpo.add(fila(new JLabel("Indicativo"), ind, gris("el que saldrá al aire")), g);
            g.gridy++;
            info.setLineWrap(true);
            info.setWrapStyleWord(true);
            JScrollPane si = new JScrollPane(info);
            si.setToolTipText("Comentario (QSL, diploma…)");
            cuerpo.add(si, g);
            g.gridy++;
            chkSpot.setOpaque(false);
            cuerpo.add(chkSpot, g);
            g.gridy++;
            bImg.addActionListener(e -> elegirImagen());
            cuerpo.add(fila(bImg, lImg), g);
            g.gridy++;
            cuerpo.add(gris("Se guarda en UTC. Se publica al crear la actividad; luego se puede cambiar o quitar en Cluster → Próximas actividades."), g);
            add(chkAnunciar, BorderLayout.NORTH);
            add(cuerpo, BorderLayout.CENTER);
            cuerpo.setVisible(!conCasilla || chkAnunciar.isSelected());
            chkAnunciar.addActionListener(e -> {
                cuerpo.setVisible(chkAnunciar.isSelected());
                Window w = SwingUtilities.getWindowAncestor(this);
                if (w != null) w.pack();
            });
        }

        private static JLabel gris(String t) {
            JLabel l = new JLabel("<html>" + t + "</html>");
            l.setForeground(GRIS);
            l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
            return l;
        }

        private static JPanel fila(JComponent... cs) {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            p.setOpaque(false);
            for (JComponent c : cs) p.add(c);
            return p;
        }

        private void elegirImagen() {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Imágenes (JPG, PNG, BMP, GIF)", "jpg", "jpeg", "png", "bmp", "gif"));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try {
                imgB64 = imagenReducida(fc.getSelectedFile());
                quitarImg = false;
                lImg.setText(fc.getSelectedFile().getName() + " · " + (imgB64.length() * 3 / 4 / 1024) + " KB");
            } catch (Exception ex) {
                imgB64 = "";
                lImg.setText("No se ha podido leer esa imagen");
            }
        }

        void cargar(Actividad a) {
            ZonedDateTime ini = Instant.ofEpochSecond(a.inicio()).atZone(ZoneOffset.UTC), fin = Instant.ofEpochSecond(a.fin()).atZone(ZoneOffset.UTC);
            zona.setSelectedItem("UTC");
            cal.elegir(ini.toLocalDate());
            desde.setText(ini.format(HM));
            hasta.setText(fin.format(HM));
            if (!fin.minusSeconds(1).toLocalDate().equals(ini.toLocalDate()) && fin.toLocalDate().isAfter(ini.toLocalDate().plusDays(0))
                    && (a.fin() - a.inicio() > 86400 || !fin.toLocalTime().isBefore(ini.toLocalTime()))) {
                chkVarios.setSelected(true);
                diaFin.setEnabled(true);
                diaFin.setText(fin.toLocalDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            }
            frec.setText(a.frec());
            ind.setText(a.ind());
            info.setText(a.info());
            chkSpot.setSelected(a.spot());
            if (!a.img().isEmpty()) lImg.setText("ya tiene imagen · elige otra para cambiarla");
        }

        boolean anunciar() { return chkAnunciar.isSelected(); }

        /** El JSON para el servidor, o «!motivo» si falta algo. */
        String json(String nombre, String aid, String club) {
            if (nombre.length() < 3) return "!Escribe el nombre de la actividad.";
            String in = ind.getText().trim().toUpperCase(Locale.ROOT);
            if (in.length() < 3) return "!Escribe el indicativo que saldrá al aire.";
            LocalDate dia = cal.elegido();
            if (dia == null) return "!Elige el día en el calendario.";
            LocalTime h1, h2;
            try {
                h1 = LocalTime.parse(desde.getText().trim().length() == 4 ? "0" + desde.getText().trim() : desde.getText().trim());
                h2 = LocalTime.parse(hasta.getText().trim().length() == 4 ? "0" + hasta.getText().trim() : hasta.getText().trim());
            } catch (Exception e) { return "!Escribe las horas como 09:00."; }
            LocalDate diaF = dia;
            if (chkVarios.isSelected()) {
                try { diaF = LocalDate.parse(diaFin.getText().trim(), DateTimeFormatter.ofPattern("d/M/yyyy")); }
                catch (Exception e) { return "!Escribe el último día como dd/mm/aaaa."; }
            }
            ZoneId z = "UTC".equals(zona.getSelectedItem()) ? ZoneOffset.UTC : ZoneId.systemDefault();
            long ini = dia.atTime(h1).atZone(z).toEpochSecond();
            long fin = diaF.atTime(h2).atZone(z).toEpochSecond();
            if (fin <= ini && !chkVarios.isSelected()) fin += 86400;   // termina pasada la medianoche
            if (fin <= ini) return "!La hora de fin tiene que ser después de la de inicio.";
            StringBuilder s = new StringBuilder("{");
            if (!aid.isEmpty()) s.append("\"aid\":\"").append(LibroGuardiaUI.jsonTexto(aid)).append("\",");
            s.append("\"nombre\":\"").append(LibroGuardiaUI.jsonTexto(nombre)).append("\",\"ind\":\"").append(LibroGuardiaUI.jsonTexto(in))
                    .append("\",\"inicio\":").append(ini).append(",\"fin\":").append(fin)
                    .append(",\"frec\":\"").append(LibroGuardiaUI.jsonTexto(frec.getText().trim())).append("\",\"info\":\"")
                    .append(LibroGuardiaUI.jsonTexto(info.getText().trim().replace('\n', ' '))).append("\",\"club\":\"").append(LibroGuardiaUI.jsonTexto(club))
                    .append("\",\"spot\":").append(chkSpot.isSelected());
            if (!imgB64.isEmpty()) s.append(",\"img\":\"").append(imgB64).append("\"");
            else if (quitarImg) s.append(",\"quitar_img\":true");
            return s.append("}").toString();
        }

        void ponerIndicativo(String t) { if (t != null && !t.isBlank()) ind.setText(t.trim().toUpperCase(Locale.ROOT)); }
    }

    /** Calendario de un mes: se elige el día pinchando; ‹ › cambian de mes. */
    static final class Calendario extends JPanel {
        private YearMonth mes = YearMonth.now(ZoneOffset.UTC);
        private LocalDate elegido;
        private final JLabel titulo = new JLabel("", SwingConstants.CENTER);
        private final JPanel dias = new JPanel(new GridLayout(0, 7, 3, 3));
        private static final String[] MESES = {"Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"};

        Calendario() {
            super(new BorderLayout(0, 4));
            setOpaque(false);
            JButton ant = new JButton("‹"), sig = new JButton("›");
            for (JButton b : new JButton[]{ant, sig}) { b.setMargin(new Insets(0, 8, 0, 8)); b.setFocusable(false); }
            ant.addActionListener(e -> { if (mes.isAfter(YearMonth.now(ZoneOffset.UTC))) { mes = mes.minusMonths(1); pintar(); } });
            sig.addActionListener(e -> { if (mes.isBefore(YearMonth.now(ZoneOffset.UTC).plusMonths(12))) { mes = mes.plusMonths(1); pintar(); } });
            titulo.setFont(titulo.getFont().deriveFont(Font.BOLD, 13f));
            JPanel n = new JPanel(new BorderLayout());
            n.setOpaque(false);
            n.add(ant, BorderLayout.WEST);
            n.add(titulo, BorderLayout.CENTER);
            n.add(sig, BorderLayout.EAST);
            dias.setOpaque(false);
            add(n, BorderLayout.NORTH);
            add(dias, BorderLayout.CENTER);
            pintar();
        }

        LocalDate elegido() { return elegido; }

        void elegir(LocalDate d) { elegido = d; mes = YearMonth.from(d); pintar(); }

        private void pintar() {
            titulo.setText(MESES[mes.getMonthValue() - 1] + " " + mes.getYear());
            dias.removeAll();
            for (String d : new String[]{"L", "M", "X", "J", "V", "S", "D"}) {
                JLabel l = new JLabel(d, SwingConstants.CENTER);
                l.setForeground(GRIS);
                dias.add(l);
            }
            LocalDate hoy = LocalDate.now(ZoneOffset.UTC);
            for (int i = 1; i < mes.atDay(1).getDayOfWeek().getValue(); i++) dias.add(new JLabel(""));
            for (int dia = 1; dia <= mes.lengthOfMonth(); dia++) {
                LocalDate f = mes.atDay(dia);
                JButton b = new JButton(String.valueOf(dia)) {
                    @Override protected void paintComponent(Graphics g0) {
                        Graphics2D g = (Graphics2D) g0.create();
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        if (f.equals(elegido)) { g.setColor(AMBAR); g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8); }
                        else if (f.equals(hoy)) { g.setColor(GRIS); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8); }
                        else if (getModel().isRollover() && isEnabled()) { g.setColor(LINEA); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8); }
                        g.dispose();
                        super.paintComponent(g0);
                    }
                };
                b.setContentAreaFilled(false);
                b.setBorderPainted(false);
                b.setFocusPainted(false);
                b.setMargin(new Insets(2, 2, 2, 2));
                b.setEnabled(!f.isBefore(hoy));
                b.setForeground(f.equals(elegido) ? new Color(0x1a1200) : TEXTO);
                b.setFont(b.getFont().deriveFont(f.equals(elegido) ? Font.BOLD : Font.PLAIN, 12f));
                b.addActionListener(e -> { elegido = f; pintar(); });
                dias.add(b);
            }
            dias.revalidate();
            dias.repaint();
        }
    }
}
