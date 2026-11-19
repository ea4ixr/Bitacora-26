import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Datos de fuera que usa DIGITALES para los colores, igual que WSJT-X (Ajustes → Colores):
 * - cty.dat (country-files.com): país (DXCC), continente, zona CQ y zona ITU de cada indicativo.
 * - Lista de usuarios de LoTW (ARRL): quién ha subido contactos a LoTW hace poco.
 * - CALL3.TXT: locator de muchos indicativos (para saber el locator cuando el mensaje no lo trae).
 * Se descargan solo al pulsar sus botones y se guardan en Documentos\Libro de Guardia\digitales.
 */
final class DatosDigitales {
    private DatosDigitales() { }

    static final String URL_CTY = "https://www.country-files.com/bigcty/cty.dat";
    static final String URL_CALL3 = "https://wsjt-x-improved.sourceforge.io/CALL3.TXT";
    static final String URL_CALL3_EME = "https://wsjt-x-improved.sourceforge.io/CALL3_EME.TXT";
    static final String URL_LOTW = "https://lotw.arrl.org/lotw-user-activity.csv";

    /** Una entidad (país) de cty.dat con los datos que corresponden al indicativo. */
    record Entidad(String nombre, String prefijo, String continente, int zonaCq, int zonaItu, boolean wae) { }

    private record Pref(Entidad ent, String cont, int cq, int itu) { }

    private static Map<String, Pref> prefijos, exactos;
    private static int largoMax = 0;
    private static boolean ctyCargado = false;
    private static Map<String, LocalDate> lotw;
    private static Map<String, String> call3;

    static File carpeta() {
        File d = new File(System.getProperty("user.home"), "Documents" + File.separator + LibroGuardiaUI.NOMBRE_APP + File.separator + "digitales");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File ficheroCty() { return new File(carpeta(), "cty.dat"); }
    static File ficheroLotw() { return new File(carpeta(), "lotw-user-activity.csv"); }
    static File ficheroCall3() { return new File(carpeta(), "CALL3.TXT"); }

    /** Descarga una dirección a un fichero (primero a uno temporal, para no dejar el bueno a medias). */
    static void descargar(String url, File destino) throws Exception {
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(java.time.Duration.ofSeconds(20)).build();
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofMinutes(3))
                .header("User-Agent", "Bitacora26").GET().build();
        File tmp = new File(destino.getParentFile(), destino.getName() + ".descargando");
        HttpResponse<java.nio.file.Path> resp = c.send(r, HttpResponse.BodyHandlers.ofFile(tmp.toPath()));
        if (resp.statusCode() != 200) { tmp.delete(); throw new Exception("el servidor ha contestado " + resp.statusCode()); }
        if (tmp.length() < 100) { tmp.delete(); throw new Exception("el archivo ha llegado vacío"); }
        Files.move(tmp.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------ cty.dat
    static synchronized void olvidarCty() { ctyCargado = false; prefijos = null; exactos = null; }

    /** Versión del cty.dat guardado ("VER20250115"), o "no descargado". */
    static String versionCty() {
        File f = ficheroCty();
        if (!f.exists()) return "no descargado";
        cargarCty();
        Entidad e = buscar("VERSION", true);
        if (e != null && e.nombre() != null) return e.nombre().replace(" ", "");
        return "descargado el " + new java.text.SimpleDateFormat("dd/MM/yyyy").format(new java.util.Date(f.lastModified()));
    }

    private static synchronized void cargarCty() {
        if (ctyCargado) return;
        ctyCargado = true;
        prefijos = new HashMap<>();
        exactos = new HashMap<>();
        largoMax = 0;
        File f = ficheroCty();
        if (!f.exists()) return;
        try {
            String todo = new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1);
            // cada entidad: cabecera con 8 campos separados por ':' y luego los prefijos hasta ';'
            Pattern cab = Pattern.compile("([^:\\r\\n][^:]*):\\s*(\\d+):\\s*(\\d+):\\s*([A-Z]{2}):\\s*([-\\d.]+):\\s*([-\\d.]+):\\s*([-\\d.]+):\\s*([^:]+):([^;]*);");
            Matcher m = cab.matcher(todo);
            Pattern pCq = Pattern.compile("\\((\\d+)\\)"), pItu = Pattern.compile("\\[(\\d+)\\]"), pCont = Pattern.compile("\\{([A-Z]{2})\\}");
            while (m.find()) {
                String nombre = m.group(1).trim();
                String prim = m.group(8).trim();
                boolean wae = prim.startsWith("*");
                Entidad ent = new Entidad(nombre, wae ? prim.substring(1) : prim, m.group(4), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), wae);
                for (String x : m.group(9).split(",")) {
                    String s = x.trim();
                    if (s.isEmpty()) continue;
                    int cq = ent.zonaCq(), itu = ent.zonaItu();
                    String cont = ent.continente();
                    Matcher a = pCq.matcher(s); if (a.find()) cq = Integer.parseInt(a.group(1));
                    a = pItu.matcher(s); if (a.find()) itu = Integer.parseInt(a.group(1));
                    a = pCont.matcher(s); if (a.find()) cont = a.group(1);
                    String limpio = s.replaceAll("\\(.*?\\)|\\[.*?]|<.*?>|\\{.*?}|~.*?~", "").trim();
                    Pref p = new Pref(ent, cont, cq, itu);
                    if (limpio.startsWith("=")) exactos.put(limpio.substring(1) + (wae ? "#W" : ""), p);
                    else {
                        prefijos.put(limpio + (wae ? "#W" : ""), p);
                        largoMax = Math.max(largoMax, limpio.length());
                    }
                }
            }
            // la versión va como un indicativo especial "=VERSION" en la entidad cuyo nombre es la versión
            for (Map.Entry<String, Pref> e : exactos.entrySet())
                if (e.getKey().startsWith("VER2") && e.getKey().length() >= 11) {
                    exactos.put("VERSION", new Pref(new Entidad(e.getKey().replace("#W", ""), "", "", 0, 0, false), "", 0, 0));
                    break;
                }
        } catch (Exception e) {
            System.err.println("No se ha podido leer cty.dat: " + e.getMessage());
        }
    }

    /** ¿Está cargado el cty.dat? (sin él no se sabe continente ni zonas). */
    static boolean hayCty() { cargarCty(); return prefijos != null && !prefijos.isEmpty(); }

    /** Entidad de un indicativo según cty.dat; con wae = false, las entidades WAE (Sicilia...) cuentan como su país. */
    static Entidad buscar(String indicativo, boolean wae) {
        if (indicativo == null || indicativo.isBlank()) return null;
        cargarCty();
        if (prefijos == null || prefijos.isEmpty()) return null;
        String c = indicativo.trim().toUpperCase().replace("<", "").replace(">", "");
        Pref p = exacto(c, wae);
        if (p == null) {
            String base = c;
            if (c.contains("/")) {   // EA8/DL1ABC, DL1ABC/P, DL1ABC/EA8: se busca por la parte que hace de prefijo
                String elegido = null;
                for (String parte : c.split("/")) {
                    if (parte.isEmpty() || parte.matches("P|M|MM|AM|QRP|A|R|\\d")) continue;
                    if (elegido == null || parte.length() < elegido.length()) elegido = parte;
                }
                if (elegido == null) return null;
                base = elegido;
                p = exacto(base, wae);
            }
            for (int n = Math.min(largoMax, base.length()); n >= 1 && p == null; n--) {
                String pre = base.substring(0, n);
                if (wae) p = prefijos.get(pre + "#W");
                if (p == null) p = prefijos.get(pre);
            }
        }
        if (p == null) return null;
        Entidad e = p.ent();
        return new Entidad(e.nombre(), e.prefijo(), p.cont(), p.cq(), p.itu(), e.wae());
    }

    private static Pref exacto(String c, boolean wae) {
        Pref p = wae ? exactos.get(c + "#W") : null;
        return p != null ? p : exactos.get(c);
    }

    // ------------------------------------------------------------------ LoTW
    static synchronized void olvidarLotw() { lotw = null; }

    private static synchronized void cargarLotw() {
        if (lotw != null) return;
        lotw = new HashMap<>();
        File f = ficheroLotw();
        if (!f.exists()) return;
        try (java.io.BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.ISO_8859_1)) {
            String l;
            while ((l = r.readLine()) != null) {
                String[] x = l.split(",");
                if (x.length < 2) continue;
                try { lotw.put(x[0].trim().toUpperCase(), LocalDate.parse(x[1].trim())); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            System.err.println("No se ha podido leer la lista de LoTW: " + e.getMessage());
        }
    }

    /** ¿El indicativo ha subido contactos a LoTW en los últimos «dias» días? */
    static boolean usuarioLotw(String indicativo, int dias) {
        if (indicativo == null) return false;
        cargarLotw();
        LocalDate d = lotw.get(indicativo.trim().toUpperCase());
        return d != null && ChronoUnit.DAYS.between(d, LocalDate.now()) <= dias;
    }

    static String estadoLotw() {
        File f = ficheroLotw();
        if (!f.exists()) return "Sin descargar";
        cargarLotw();
        return String.format("%,d usuarios · descargada el %s", lotw.size(),
                new java.text.SimpleDateFormat("dd/MM/yyyy").format(new java.util.Date(f.lastModified())));
    }

    // ------------------------------------------------------------------ CALL3.TXT
    static synchronized void olvidarCall3() { call3 = null; }

    private static synchronized void cargarCall3() {
        if (call3 != null) return;
        call3 = new HashMap<>();
        File f = ficheroCall3();
        if (!f.exists()) return;
        try (java.io.BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.ISO_8859_1)) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("//")) continue;
                String[] x = l.split(",");
                if (x.length >= 2 && x[1].trim().matches("[A-Ra-r]{2}\\d{2}.*")) call3.put(x[0].trim().toUpperCase(), x[1].trim().toUpperCase());
            }
        } catch (Exception e) {
            System.err.println("No se ha podido leer CALL3.TXT: " + e.getMessage());
        }
    }

    /** Locator de CALL3.TXT para un indicativo, o "". */
    static String gridCall3(String indicativo) {
        if (indicativo == null) return "";
        cargarCall3();
        String g = call3.get(indicativo.trim().toUpperCase());
        return g == null ? "" : g;
    }

    static String estadoCall3() {
        File f = ficheroCall3();
        if (!f.exists()) return "no descargado";
        cargarCall3();
        return String.format("%,d indicativos · descargado el %s", call3.size(),
                new java.text.SimpleDateFormat("dd/MM/yyyy").format(new java.util.Date(f.lastModified())));
    }
}
