import javax.swing.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Main {

    private static final String DLL_X64 = "jacob-1.21-x64.dll";
    private static final String DLL_X86 = "jacob-1.21-x86.dll";

    @SuppressWarnings("unused")
    public static void main(String[] args) {
        // === PANTALLAS BAJAS (portátiles): toda la ventana al 70 % para que se vea entera ===
        if (ajustarTamanoPantalla(args)) return;

        // === CARGA DE JACOB (busca la DLL junto a la aplicacion y en el directorio de trabajo) ===
        try {
            boolean cargada = false;
            for (File f : obtenerDllsJacob()) {
                if (!f.exists()) continue;
                try {
                    String ruta = f.getAbsolutePath();
                    System.out.println("=== [INIT] Cargando JACOB desde: " + ruta);
                    // JACOB leera esta propiedad y cargara exactamente esta DLL (evita que
                    // intente buscarla por nombre en java.library.path).
                    System.setProperty("jacob.dll.path", ruta);
                    System.load(ruta);
                    System.out.println("=== [INIT] JACOB cargado correctamente ===");
                    cargada = true;
                    break;
                } catch (Throwable t) {
                    System.err.println("=== [INIT] Fallo al cargar " + f + ": " + t.getMessage());
                    System.clearProperty("jacob.dll.path");
                }
            }

            if (!cargada) {
                System.err.println("=== [INIT] No se encontro la DLL de JACOB (" + DLL_X64 + " / " + DLL_X86 + ") en ninguna ruta conocida ===");
            }
        } catch (Throwable t) {
            System.err.println("=== [INIT] Error inesperado cargando JACOB ===");
            System.err.println("Detalle: " + t.getMessage());
        }

        // === INICIALIZACION DE LA BASE DE DATOS ===
        // La ruta es fija y la gestiona DatabaseManager. Solo hay que asegurarse
        // de que las tablas existan con el esquema actual.
        LibroGuardiaUI.DatabaseManager.inicializarBaseDatos();

        // === LANZAR INTERFAZ GRAFICA ===
        javax.swing.SwingUtilities.invokeLater(() -> {
            LibroGuardiaUI ventana = new LibroGuardiaUI();
            ventana.setVisible(true);
        });
    }

    // Por debajo de esta altura útil de pantalla (en puntos reales), todo se dibuja al 70 %
    private static final int ALTO_PANTALLA_BAJA = 900;
    private static final String ESCALA_PANTALLA_BAJA = "0.70";

    /**
     * En pantallas bajas (portátiles de 1366x768, 1600x900...) toda la ventana se dibuja al 70 %; en las demás, como siempre.
     * Lo elegido se guarda en la configuración (ESCALA_PANTALLA). Si la pantalla ha cambiado, se guarda lo nuevo y el
     * programa se vuelve a abrir solo una vez con el tamaño correcto. Devuelve true si esta copia debe terminar.
     */
    private static boolean ajustarTamanoPantalla(String[] args) {
        try {
            String guardada = LibroGuardiaUI.ConfigApp.get("ESCALA_PANTALLA", "").trim();
            if (System.getProperty("sun.java2d.uiScale") == null && !guardada.isEmpty())
                System.setProperty("sun.java2d.uiScale", guardada);   // antes de crear ninguna ventana
            java.awt.GraphicsConfiguration gc = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
            double escala = gc.getDefaultTransform().getScaleY();
            java.awt.Insets ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc);
            double altoReal = (gc.getBounds().height - ins.top - ins.bottom) * escala;
            String quiere = altoReal < ALTO_PANTALLA_BAJA ? ESCALA_PANTALLA_BAJA : "";
            if (quiere.equals(guardada)) return false;
            LibroGuardiaUI.ConfigApp.put("ESCALA_PANTALLA", quiere);
            System.out.println("=== [INIT] Pantalla: " + Math.round(altoReal) + " puntos de alto -> " + (quiere.isEmpty() ? "tamaño normal" : "al 70 %"));
            // Se vuelve a abrir el programa (solo con el programa empaquetado, y una sola vez)
            if ("1".equals(System.getenv("B26_REINICIO"))) return false;
            String exe = ProcessHandle.current().info().command().orElse("");
            String n = new File(exe).getName().toLowerCase();
            if (exe.isEmpty() || n.equals("java.exe") || n.equals("javaw.exe") || n.equals("java")) return false;
            List<String> orden = new ArrayList<>();
            orden.add(exe);
            for (String a : args) orden.add(a);
            ProcessBuilder pb = new ProcessBuilder(orden);
            pb.environment().put("B26_REINICIO", "1");
            pb.start();
            System.exit(0);
            return true;
        } catch (Throwable t) {
            System.err.println("=== [INIT] Tamaño de pantalla: " + t.getMessage());
            return false;
        }
    }

    /** DLLs candidatas, la que corresponde a la JVM en uso primero. */
    static List<File> obtenerDllsJacob() {
        // En una JVM de 64 bits hay que cargar la DLL x64; en una de 32, la x86.
        boolean jvm64 = !"32".equals(System.getProperty("sun.arch.data.model"));
        String[] nombres = jvm64 ? new String[]{DLL_X64, DLL_X86} : new String[]{DLL_X86, DLL_X64};

        List<File> carpetas = new ArrayList<>();
        try {
            // Carpeta donde esta el JAR (con jpackage: <app>\app)
            File codigo = new File(Main.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            File carpetaApp = codigo.isFile() ? codigo.getParentFile() : codigo;
            carpetas.add(carpetaApp);
            carpetas.add(new File(carpetaApp, "app"));
            if (carpetaApp.getParentFile() != null) {
                carpetas.add(carpetaApp.getParentFile());
            }
        } catch (Throwable ignored) {
        }
        // Directorio de trabajo (al ejecutar desde IntelliJ es la raiz del proyecto)
        File userDir = new File(System.getProperty("user.dir", "."));
        carpetas.add(userDir);
        carpetas.add(new File(userDir, "app"));

        List<File> resultado = new ArrayList<>();
        for (String nombre : nombres) {
            for (File carpeta : carpetas) {
                resultado.add(new File(carpeta, nombre));
            }
        }
        return resultado;
    }
}
