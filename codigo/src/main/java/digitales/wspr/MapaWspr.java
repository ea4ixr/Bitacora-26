package digitales.wspr;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Mapa del mundo de WSPR (sin internet: usa los contornos de mapa_mundo.dat.gz que ya lleva Bitácora 26).
 * Verde: balizas que oigo yo. Naranja: estaciones que me oyen a mí.
 * Rueda del ratón: acercar/alejar. Arrastrar: mover. Doble clic: ver el mundo entero.
 */
final class MapaWspr extends JPanel {

    static final Color VERDE = new Color(60, 200, 110), NARANJA = new Color(255, 150, 40);
    private static Path2D.Float tierra, fronteras;
    private static boolean cargado = false;

    private record Punto(double lat, double lon, String nombre, Color color, String pais) {
        Punto(double lat, double lon, String nombre, Color color) { this(lat, lon, nombre, color, ""); }
    }

    // Agrupado por países (solo «¿Quién me oye?»): un punto por país; al pinchar un país se ven sus estaciones
    private boolean porPais = false;
    private String paisAbierto;
    private record Pais(String nombre, double lat, double lon, int n) { }
    private final List<Pais> paises = new ArrayList<>();

    private double[] casa;
    private String miNombre = "";
    private final List<Punto> oigo = new ArrayList<>(), meOyen = new ArrayList<>();

    static final Color SELECCION = new Color(255, 215, 0);
    private String seleccionado;
    java.util.function.Consumer<String> alPulsar;
    java.util.function.BiConsumer<String, Point> alMenu;   // clic derecho sobre una estación   // indicativo pulsado, o null si se pulsa donde no hay nada

    void seleccionar(String indicativo) {
        seleccionado = indicativo;
        if (porPais && indicativo != null)   // al elegir en la lista se abre su país
            for (Punto p : meOyen) if (p.nombre.equals(indicativo)) paisAbierto = p.pais;
        repaint();
    }

    /** Estaciones que me oyen agrupadas por país: {locator, indicativo, país}. */
    void ponerMeOyenPorPais(List<String[]> estaciones) {
        porPais = true;
        meOyen.clear();
        java.util.Map<String, double[]> suma = new java.util.LinkedHashMap<>();   // país → {sumaLat, sumaX, sumaY, n}
        for (String[] s : estaciones) {
            double[] p = VentanaWspr.latLon(s[0]);
            if (p == null) continue;
            String pais = s.length > 2 && s[2] != null && !s[2].isBlank() ? s[2] : "?";
            meOyen.removeIf(x -> x.nombre.equals(s[1]));
            meOyen.add(new Punto(p[0], p[1], s[1], NARANJA, pais));
        }
        for (Punto p : meOyen) {
            double[] a = suma.computeIfAbsent(p.pais, k -> new double[4]);
            a[0] += p.lat; a[1] += Math.cos(Math.toRadians(p.lon)); a[2] += Math.sin(Math.toRadians(p.lon)); a[3]++;
        }
        paises.clear();
        for (var e : suma.entrySet()) {
            double[] a = e.getValue();
            paises.add(new Pais(e.getKey(), a[0] / a[3], Math.toDegrees(Math.atan2(a[2], a[1])), (int) a[3]));
        }
        if (paisAbierto != null && paisAbiertoObj() == null) paisAbierto = null;
        repaint();
    }

    private Pais paisEn(int mx, int my) {
        Pais mejor = null;
        double dMin = 12;
        for (Pais p : paises) {
            double[] q = aPantalla(p.lat, p.lon);
            double d = Math.hypot(q[0] - mx, q[1] - my);
            if (d < dMin) { dMin = d; mejor = p; }
        }
        return mejor;
    }

    private Pais paisAbiertoObj() {
        for (Pais p : paises) if (p.nombre.equals(paisAbierto)) return p;
        return null;
    }

    private String indicativoEn(int mx, int my) {
        if (porPais) {   // solo cuentan las estaciones del país abierto
            String mejor = null;
            double dMin = 10;
            for (Punto p : meOyen) {
                if (!p.pais.equals(paisAbierto)) continue;
                double[] q = aPantalla(p.lat, p.lon);
                double d = Math.hypot(q[0] - mx, q[1] - my);
                if (d < dMin) { dMin = d; mejor = p.nombre; }
            }
            return mejor;
        }
        String mejor = null;
        double dMin = 10;
        for (List<Punto> l : List.of(oigo, meOyen))
            for (Punto p : l) {
                double[] q = aPantalla(p.lat, p.lon);
                double d = Math.hypot(q[0] - mx, q[1] - my);
                if (d < dMin) { dMin = d; mejor = p.nombre; }
            }
        return mejor;
    }

    private double escala = 1, centroLon = 0, centroLat = 20;
    private Point desde;

    MapaWspr() {
        setPreferredSize(new Dimension(520, 420));
        setBackground(new Color(18, 32, 52));
        cargar();
        setToolTipText("<html>Verde: balizas que oyes tú · Naranja: quién te oye a ti<br>"
                + "Rueda del ratón: acercar / alejar · Arrastrar: mover · Doble clic: mundo entero</html>");
        MouseAdapter m = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { desde = e.getPoint(); menu(e); }
            @Override public void mouseReleased(MouseEvent e) { desde = null; menu(e); }
            private void menu(MouseEvent e) {
                if (!e.isPopupTrigger() || alMenu == null) return;
                String est = indicativoEn(e.getX(), e.getY());
                if (est == null) return;
                seleccionado = est; repaint();
                if (alPulsar != null) alPulsar.accept(est);
                alMenu.accept(est, e.getPoint());
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (desde == null) return;
                double k = gradosPorPixel();
                centroLon -= (e.getX() - desde.x) * k;
                centroLat += (e.getY() - desde.y) * k;
                centroLat = Math.max(-80, Math.min(80, centroLat));
                desde = e.getPoint();
                repaint();
            }
            @Override public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                escala = Math.max(1, Math.min(12, escala * (e.getWheelRotation() < 0 ? 1.3 : 1 / 1.3)));
                repaint();
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (porPais && e.getClickCount() == 1 && SwingUtilities.isLeftMouseButton(e)) {
                    String est = indicativoEn(e.getX(), e.getY());
                    if (est != null) { seleccionado = est; repaint(); if (alPulsar != null) alPulsar.accept(est); return; }
                    Pais p = paisEn(e.getX(), e.getY());
                    paisAbierto = p == null ? null : p.nombre;   // pinchar fuera: se vuelve a ver todos los países
                    if (p != null) { centroLon = p.lon; centroLat = Math.max(-80, Math.min(80, p.lat)); escala = Math.max(escala, 4); }
                    seleccionado = null;
                    repaint();
                    if (alPulsar != null) alPulsar.accept(null);
                    return;
                }
                if (e.getClickCount() == 1 && SwingUtilities.isLeftMouseButton(e)) {
                    seleccionado = indicativoEn(e.getX(), e.getY());
                    repaint();
                    if (alPulsar != null) alPulsar.accept(seleccionado);
                    return;
                }
                if (e.getClickCount() == 2) { escala = 1; centroLon = casa == null ? 0 : casa[1]; centroLat = 20; repaint(); }
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
    }

    void setCasa(double[] latLon, String nombre) {
        casa = latLon;
        miNombre = nombre == null ? "" : nombre;
        if (casa != null) centroLon = casa[1];
        repaint();
    }

    void anadirOigo(String locator, String indicativo) {
        double[] p = VentanaWspr.latLon(locator);
        if (p == null) return;
        oigo.removeIf(x -> x.nombre.equals(indicativo));
        oigo.add(new Punto(p[0], p[1], indicativo, VERDE));
        while (oigo.size() > 300) oigo.remove(0);
        repaint();
    }

    void ponerMeOyen(List<String[]> estaciones) {   // {locator, indicativo}
        meOyen.clear();
        for (String[] s : estaciones) {
            double[] p = VentanaWspr.latLon(s[0]);
            if (p == null) continue;
            meOyen.removeIf(x -> x.nombre.equals(s[1]));
            meOyen.add(new Punto(p[0], p[1], s[1], NARANJA));
        }
        repaint();
    }

    void borrarOigo() { oigo.clear(); repaint(); }

    private double gradosPorPixel() { return 360.0 / (Math.max(1, getWidth()) * escala); }

    private double[] aPantalla(double lat, double lon) {
        double k = gradosPorPixel();
        double dx = lon - centroLon;
        while (dx > 180) dx -= 360;
        while (dx < -180) dx += 360;
        return new double[]{getWidth() / 2.0 + dx / k, getHeight() / 2.0 - (lat - centroLat) / k};
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        double k = gradosPorPixel();

        // Tierra: se dibuja 3 veces (desplazada 360º) para que no haya cortes al mover el mapa
        for (int vuelta = -1; vuelta <= 1; vuelta++) {
            java.awt.geom.AffineTransform t = new java.awt.geom.AffineTransform();
            t.translate(w / 2.0 + (-centroLon + 360 * vuelta) / k, h / 2.0 + centroLat / k);
            t.scale(1 / k, 1 / k);
            if (tierra != null) {
                Shape s = t.createTransformedShape(tierra);
                g.setColor(new Color(52, 74, 96));
                g.fill(s);
                g.setColor(new Color(90, 115, 140));
                g.setStroke(new BasicStroke(0.8f));
                g.draw(s);
            }
            if (fronteras != null) {
                g.setColor(new Color(80, 104, 128));
                g.setStroke(new BasicStroke(0.5f));
                g.draw(t.createTransformedShape(fronteras));
            }
        }

        if (porPais) { pintarPorPais(g, w, h); g.dispose(); return; }
        if (casa != null) {
            g.setStroke(new BasicStroke(1.4f));
            for (Punto p : oigo) ruta(g, p, new Color(VERDE.getRed(), VERDE.getGreen(), VERDE.getBlue(), 150));
            for (Punto p : meOyen) ruta(g, p, new Color(NARANJA.getRed(), NARANJA.getGreen(), NARANJA.getBlue(), 170));
        }
        Punto sel = null;
        for (List<Punto> l : List.of(oigo, meOyen)) for (Punto p : l) if (p.nombre.equals(seleccionado)) sel = p;
        if (sel != null && casa != null) { g.setStroke(new BasicStroke(3f)); ruta(g, sel, SELECCION); g.setStroke(new BasicStroke(1.4f)); }
        g.setFont(getFont().deriveFont(Font.BOLD, 10f));
        for (Punto p : oigo) if (p != sel) marca(g, p.lat, p.lon, p.nombre, p.color);
        for (Punto p : meOyen) if (p != sel) marca(g, p.lat, p.lon, p.nombre, p.color);
        if (sel != null) { g.setFont(getFont().deriveFont(Font.BOLD, 12f)); marca(g, sel.lat, sel.lon, sel.nombre, SELECCION); g.setFont(getFont().deriveFont(Font.BOLD, 10f)); }
        if (casa != null) marca(g, casa[0], casa[1], miNombre, Color.WHITE);

        // Leyenda
        g.setFont(getFont().deriveFont(11f));
        g.setColor(new Color(0, 0, 0, 140));
        g.fillRoundRect(6, h - 44, 210, 38, 8, 8);
        g.setColor(VERDE);
        g.fillOval(14, h - 37, 9, 9);
        g.drawString("Le oigo yo (" + oigo.size() + ")", 28, h - 28);
        g.setColor(NARANJA);
        g.fillOval(14, h - 21, 9, 9);
        g.drawString("Me oye a mí (" + meOyen.size() + ")", 28, h - 12);
        g.dispose();
    }

    private void marca(Graphics2D g, double lat, double lon, String nombre, Color c) {
        double[] p = aPantalla(lat, lon);
        int x = (int) p[0], y = (int) p[1];
        if (x < -20 || y < -20 || x > getWidth() + 20 || y > getHeight() + 20) return;
        g.setColor(c);
        int rr = c == SELECCION ? 6 : 4;
        g.fillOval(x - rr, y - rr, 2 * rr, 2 * rr);
        g.setColor(Color.BLACK);
        g.drawOval(x - rr, y - rr, 2 * rr, 2 * rr);
        if (escala >= 1.6 || c == Color.WHITE || c == SELECCION) {
            g.setColor(new Color(0, 0, 0, 150));
            int ancho = g.getFontMetrics().stringWidth(nombre);
            g.fillRect(x + 6, y - 12, ancho + 4, 13);
            g.setColor(c);
            g.drawString(nombre, x + 8, y - 2);
        }
    }

    private void pintarPorPais(Graphics2D g, int w, int h) {
        Color linea = new Color(NARANJA.getRed(), NARANJA.getGreen(), NARANJA.getBlue(), 170);
        Pais abierto = paisAbiertoObj();
        g.setStroke(new BasicStroke(1.4f));
        if (abierto == null) {
            if (casa != null) for (Pais p : paises) ruta(g, casa, p.lat, p.lon, linea);
        } else {
            if (casa != null) { g.setStroke(new BasicStroke(2.2f)); ruta(g, casa, abierto.lat, abierto.lon, linea); g.setStroke(new BasicStroke(1.4f)); }
            double[] desdeP = {abierto.lat, abierto.lon};
            for (Punto p : meOyen) if (p.pais.equals(abierto.nombre))
                ruta(g, desdeP, p.lat, p.lon, p.nombre.equals(seleccionado) ? SELECCION : linea);
        }
        g.setFont(getFont().deriveFont(Font.BOLD, 10f));
        for (Pais p : paises) {   // un punto por país, con cuántas estaciones
            double[] q = aPantalla(p.lat, p.lon);
            int x = (int) q[0], y = (int) q[1];
            boolean esAbierto = p == abierto;
            int rr = esAbierto ? 8 : 6;
            g.setColor(esAbierto ? SELECCION : abierto == null ? NARANJA : new Color(150, 110, 60));
            g.fillOval(x - rr, y - rr, 2 * rr, 2 * rr);
            g.setColor(Color.BLACK);
            g.drawOval(x - rr, y - rr, 2 * rr, 2 * rr);
            if (abierto == null || esAbierto || escala >= 1.6) {
                String txt = p.nombre + " (" + p.n + ")";
                int ancho = g.getFontMetrics().stringWidth(txt);
                g.setColor(new Color(0, 0, 0, 150));
                g.fillRect(x + 8, y - 12, ancho + 4, 13);
                g.setColor(esAbierto ? SELECCION : NARANJA);
                g.drawString(txt, x + 10, y - 2);
            }
        }
        if (abierto != null) {
            Punto sel = null;
            for (Punto p : meOyen) if (p.pais.equals(abierto.nombre)) {
                if (p.nombre.equals(seleccionado)) sel = p; else marca(g, p.lat, p.lon, p.nombre, Color.WHITE);
            }
            if (sel != null) { g.setFont(getFont().deriveFont(Font.BOLD, 12f)); marca(g, sel.lat, sel.lon, sel.nombre, SELECCION); }
        }
        g.setFont(getFont().deriveFont(Font.BOLD, 10f));
        if (casa != null) marca(g, casa[0], casa[1], miNombre, Color.WHITE);

        g.setFont(getFont().deriveFont(11f));
        String l1 = abierto == null ? "Países que te oyen: " + paises.size() + "  ·  estaciones: " + meOyen.size()
                : abierto.nombre + ": " + abierto.n + (abierto.n == 1 ? " estación" : " estaciones");
        String l2 = abierto == null ? "Pincha en un país para ver sus estaciones" : "Pincha fuera para volver a todos los países";
        int ancho = Math.max(g.getFontMetrics().stringWidth(l1), g.getFontMetrics().stringWidth(l2)) + 34;
        g.setColor(new Color(0, 0, 0, 140));
        g.fillRoundRect(6, h - 44, ancho, 38, 8, 8);
        g.setColor(NARANJA);
        g.fillOval(14, h - 37, 9, 9);
        g.drawString(l1, 28, h - 28);
        g.setColor(new Color(200, 200, 200));
        g.drawString(l2, 28, h - 12);
    }

    /** Ruta más corta (círculo máximo) desde mi casa hasta el punto. */
    private void ruta(Graphics2D g, Punto b, Color c) { ruta(g, casa, b.lat, b.lon, c); }

    /** Ruta más corta (círculo máximo) entre dos puntos. */
    private void ruta(Graphics2D g, double[] desdeP, double lat2, double lon2, Color c) {
        double f1 = Math.toRadians(desdeP[0]), l1 = Math.toRadians(desdeP[1]);
        double f2 = Math.toRadians(lat2), l2 = Math.toRadians(lon2);
        double d = 2 * Math.asin(Math.sqrt(Math.pow(Math.sin((f2 - f1) / 2), 2)
                + Math.cos(f1) * Math.cos(f2) * Math.pow(Math.sin((l2 - l1) / 2), 2)));
        if (d < 1e-6) return;
        g.setColor(c);
        int n = 48;
        double[] ant = null;
        for (int i = 0; i <= n; i++) {
            double f = (double) i / n;
            double A = Math.sin((1 - f) * d) / Math.sin(d), B = Math.sin(f * d) / Math.sin(d);
            double x = A * Math.cos(f1) * Math.cos(l1) + B * Math.cos(f2) * Math.cos(l2);
            double y = A * Math.cos(f1) * Math.sin(l1) + B * Math.cos(f2) * Math.sin(l2);
            double z = A * Math.sin(f1) + B * Math.sin(f2);
            double[] p = aPantalla(Math.toDegrees(Math.atan2(z, Math.sqrt(x * x + y * y))), Math.toDegrees(Math.atan2(y, x)));
            if (ant != null && Math.abs(p[0] - ant[0]) < getWidth() / 2.0)
                g.drawLine((int) ant[0], (int) ant[1], (int) p[0], (int) p[1]);
            ant = p;
        }
    }

    private static synchronized void cargar() {
        if (cargado) return;
        cargado = true;
        tierra = new Path2D.Float(Path2D.WIND_EVEN_ODD);
        fronteras = new Path2D.Float();
        try (java.io.InputStream is = MapaWspr.class.getResourceAsStream("/mapa_mundo.dat.gz")) {
            if (is == null) return;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.util.zip.GZIPInputStream(is), java.nio.charset.StandardCharsets.US_ASCII));
            String linea;
            while ((linea = br.readLine()) != null) {
                if (linea.length() < 3) continue;
                char tipo = linea.charAt(0);
                Path2D.Float destino = tipo == 'T' ? tierra : fronteras;
                String[] pts = linea.substring(2).split(" ");
                for (int i = 0; i < pts.length; i++) {
                    int coma = pts[i].indexOf(',');
                    if (coma < 0) continue;
                    float lon = Integer.parseInt(pts[i].substring(0, coma)) / 100f;
                    float lat = Integer.parseInt(pts[i].substring(coma + 1)) / 100f;
                    if (i == 0) destino.moveTo(lon, -lat); else destino.lineTo(lon, -lat);
                }
                if (tipo == 'T') destino.closePath();
            }
        } catch (Exception e) {
            System.err.println("No se pudo cargar el mapa WSPR: " + e.getMessage());
        }
    }
}
