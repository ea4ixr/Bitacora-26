import digitales.EmisorDigitales;
import digitales.MotorDigitales;

import javax.swing.*;
import java.awt.*;

/**
 * Archivo → Configurar audio (como en WSJT-X): la tarjeta de sonido de la emisora y el canal de la entrada y de la salida.
 * Lo usa WSPR; DIGITALES tiene su propia tarjeta y canales en sus Ajustes.
 */
final class ConfigAudio {
    private static final String[][] CANALES = {{"MONO", "Mono"}, {"IZQ", "Izquierdo"}, {"DCHA", "Derecho"}, {"AUTO", "Automático"}};

    /** Al arrancar el programa: se aplica lo guardado. */
    static void aplicarAlArrancar() {
        MotorDigitales.CANAL = LibroGuardiaUI.ConfigApp.get("AUDIO_CANAL", "AUTO");
        EmisorDigitales.CANAL_SALIDA = LibroGuardiaUI.ConfigApp.get("AUDIO_CANAL_SALIDA", "AUTO");
        LibroGuardiaUI.ConfigApp.quitar("AUDIO_ESCUCHA");     // de una prueba anterior que ya no existe
        LibroGuardiaUI.ConfigApp.quitar("AUDIO_ALTAVOCES");
        // Desde la 1.39 DIGITALES tiene su propia tarjeta; la primera vez, la general empieza siendo la que había
        if ("?".equals(LibroGuardiaUI.ConfigApp.get("AUDIO_ENTRADA", "?")))
            LibroGuardiaUI.ConfigApp.put("AUDIO_ENTRADA", LibroGuardiaUI.ConfigApp.get("DIGITALES_ENTRADA", ""));
        if ("?".equals(LibroGuardiaUI.ConfigApp.get("AUDIO_SALIDA", "?")))
            LibroGuardiaUI.ConfigApp.put("AUDIO_SALIDA", LibroGuardiaUI.ConfigApp.get("DIGITALES_SALIDA", ""));
    }

    /** Código guardado (MONO, IZQ, DCHA, AUTO) del canal elegido en un desplegable de comboCanal. */
    static String codigoCanal(JComboBox<String> c) { return CANALES[Math.max(0, c.getSelectedIndex())][0]; }

    static JComboBox<String> comboCanal(String guardado) {
        JComboBox<String> c = new JComboBox<>();
        for (String[] x : CANALES) c.addItem(x[1]);
        for (int i = 0; i < CANALES.length; i++) if (CANALES[i][0].equals(guardado)) c.setSelectedIndex(i);
        return c;
    }

    private static JComboBox<String> comboTarjeta(java.util.List<String> nombres, String guardado) {
        JComboBox<String> c = new JComboBox<>();
        c.addItem("(la de por defecto de Windows)");
        for (String n : nombres) c.addItem(n);
        if (!guardado.isEmpty()) c.setSelectedItem(guardado);
        return c;
    }

    static void abrir(JFrame padre) {
        JComboBox<String> entrada = comboTarjeta(MotorDigitales.entradas(), LibroGuardiaUI.ConfigApp.get("AUDIO_ENTRADA", ""));
        JComboBox<String> canalEnt = comboCanal(LibroGuardiaUI.ConfigApp.get("AUDIO_CANAL", "AUTO"));
        JComboBox<String> salida = comboTarjeta(EmisorDigitales.salidas(), LibroGuardiaUI.ConfigApp.get("AUDIO_SALIDA", ""));
        JComboBox<String> canalSal = comboCanal(LibroGuardiaUI.ConfigApp.get("AUDIO_CANAL_SALIDA", "AUTO"));

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 6, 5, 6);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        Object[][] filas = {{"Entrada:", entrada, canalEnt}, {"Salida:", salida, canalSal}};
        int y = 0;
        for (Object[] f : filas) {
            g.gridx = 0; g.gridy = y; g.weightx = 0; p.add(new JLabel((String) f[0]), g);
            g.gridx = 1; g.weightx = 1; p.add((Component) f[1], g);
            g.gridx = 2; g.weightx = 0; p.add((Component) f[2], g);
            y++;
        }
        g.gridx = 0; g.gridy = y; g.gridwidth = 3;
        p.add(new JLabel("<html><span style='color:gray'>Entrada: lo que llega de la emisora. Salida: lo que se transmite.<br>"
                + "Canal: Mono (los dos juntos), Izquierdo, Derecho o Automático (en la entrada, el que más suena; en la salida, por los dos).<br>"
                + "Lo usa WSPR. DIGITALES (FT8, FT4, RTTY) tiene los suyos en su ventana de Ajustes.</span></html>"), g);

        int r = JOptionPane.showConfirmDialog(padre, p, "Configurar audio", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        LibroGuardiaUI.ConfigApp.put("AUDIO_ENTRADA", entrada.getSelectedIndex() <= 0 ? "" : (String) entrada.getSelectedItem());
        LibroGuardiaUI.ConfigApp.put("AUDIO_SALIDA", salida.getSelectedIndex() <= 0 ? "" : (String) salida.getSelectedItem());
        String ce = CANALES[Math.max(0, canalEnt.getSelectedIndex())][0], cs = CANALES[Math.max(0, canalSal.getSelectedIndex())][0];
        LibroGuardiaUI.ConfigApp.put("AUDIO_CANAL", ce);
        LibroGuardiaUI.ConfigApp.put("AUDIO_CANAL_SALIDA", cs);
        MotorDigitales.CANAL = ce;
        EmisorDigitales.CANAL_SALIDA = cs;
        JOptionPane.showMessageDialog(padre, "Audio guardado.\nSi cambias la tarjeta y WSPR está abierta, ciérrala y ábrela para que coja la nueva.", "Configurar audio", JOptionPane.INFORMATION_MESSAGE);
    }
}
