package digitales;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Mensajes de 77 bits de FT8/FT4: convertir los bits en texto (todos los tipos) y el texto
 * de los mensajes normales en bits. Hecho siguiendo packjt77 de WSJT-X (K1JT y colaboradores, GPL).
 * Guarda los indicativos oídos para poder mostrar los que llegan "resumidos" (hash) como &lt;EA4XYZ&gt;.
 */
public final class Ft8Mensaje {
    static final int NTOKENS = 2063592, MAX22 = 4194304, MAXGRID4 = 32400;
    private static final String C38 = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ/";
    private static final String A1 = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String A2 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String A3 = "0123456789";
    private static final String A4 = " ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String C42 = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?";

    private static final String[] SECCIONES = {"AB", "AK", "AL", "AR", "AZ", "BC", "CO", "CT", "DE", "EB",
            "EMA", "ENY", "EPA", "EWA", "GA", "GH", "IA", "ID", "IL", "IN", "KS", "KY", "LA", "LAX", "NS", "MB", "MDC", "ME", "MI", "MN",
            "MO", "MS", "MT", "NC", "ND", "NE", "NFL", "NH", "NL", "NLI", "NM", "NNJ", "NNY", "TER", "NTX", "NV", "OH", "OK", "ONE", "ONN",
            "ONS", "OR", "ORG", "PAC", "PR", "QC", "RI", "SB", "SC", "SCV", "SD", "SDG", "SF", "SFL", "SJV", "SK", "SNJ", "STX", "SV", "TN",
            "UT", "VA", "VI", "VT", "WCF", "WI", "WMA", "WNY", "WPA", "WTX", "WV", "WWA", "WY", "DX", "PE", "NB"};
    private static final String[] ESTADOS;
    static {
        String[] base = {"AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
                "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
                "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "NB", "NS", "QC", "ON", "MB", "SK", "AB", "BC", "NWT", "NF",
                "LB", "NU", "YT", "PEI", "DC", "DR", "FR", "GD", "GR", "OV", "ZH", "ZL"};
        ESTADOS = new String[171];
        for (int i = 0; i < 171; i++) ESTADOS[i] = i < base.length ? base[i] : String.format("X%02d", i - base.length + 1);
    }

    // ---- Indicativos recordados (para los resumidos de 10, 12 y 22 bits) ----
    private final String[] llamadas10 = new String[1024];
    private final String[] llamadas12 = new String[4096];
    private final Deque<long[]> orden22 = new ArrayDeque<>();
    private final Map<Integer, String> llamadas22 = new HashMap<>();
    private String miIndicativo = "", suIndicativo = "";
    private int miHash10 = -1, miHash12 = -1, miHash22 = -1, suHash10 = -1;

    /** Mi indicativo y el del corresponsal (para reconocer los mensajes resumidos dirigidos a mí). */
    public synchronized void ponerIndicativos(String mio, String suyo) {
        mio = mio == null ? "" : mio.trim().toUpperCase();
        suyo = suyo == null ? "" : suyo.trim().toUpperCase();
        if (!mio.equals(miIndicativo)) {
            miIndicativo = mio;
            if (mio.length() > 2) {
                guardar(mio);
                miHash10 = hash(mio, 10); miHash12 = hash(mio, 12); miHash22 = hash(mio, 22);
            } else { miHash10 = miHash12 = miHash22 = -1; }
        }
        suIndicativo = suyo;
        suHash10 = suyo.length() > 2 ? hash(suyo, 10) : -1;
    }

    /** Resumen (hash) de m bits de un indicativo, como ihashcall de WSJT-X. */
    static int hash(String c, int m) {
        long n8 = 0;
        for (int i = 0; i < 11; i++) {
            char ch = i < c.length() ? c.charAt(i) : ' ';
            int j = C38.indexOf(ch);
            n8 = 38L * n8 + j;
        }
        long p = 47055833459L * n8;
        return (int) (p >>> (64 - m));
    }

    /** Guarda un indicativo oído (para luego reconocer sus resúmenes). */
    public synchronized void guardar(String c) {
        if (c == null) return;
        c = c.trim();
        if (c.isEmpty() || c.startsWith("<...>")) return;
        if (c.startsWith("<")) c = c.substring(1);
        int k = c.indexOf('>');
        if (k >= 0) c = c.substring(0, k);
        c = c.trim();
        if (c.length() < 3) return;
        int n10 = hash(c, 10), n12 = hash(c, 12), n22 = hash(c, 22);
        if (!c.equals(miIndicativo)) { llamadas10[n10] = c; llamadas12[n12] = c; }
        if (!llamadas22.containsKey(n22)) {
            orden22.addFirst(new long[]{n22});
            if (orden22.size() > 1000) llamadas22.remove((int) orden22.removeLast()[0]);
        }
        llamadas22.put(n22, c);
    }

    private synchronized String de10(int n) { return n >= 0 && n < 1024 && llamadas10[n] != null ? "<" + llamadas10[n] + ">" : "<...>"; }
    private synchronized String de12(int n) { return n >= 0 && n < 4096 && llamadas12[n] != null ? "<" + llamadas12[n] + ">" : "<...>"; }
    private synchronized String de22(int n) { String c = llamadas22.get(n); return c != null ? "<" + c + ">" : "<...>"; }

    // ---- Bits ----
    /** Bits 77 (como 0/1) -> número a partir de la posición desde (1..77, como en WSJT-X), con n bits. */
    private static long leer(int[] b, int desde, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 1) | (b[desde - 1 + i] & 1);
        return v;
    }

    private static boolean esLetra(char c) { return c >= 'A' && c <= 'Z'; }
    private static boolean esCifra(char c) { return c >= '0' && c <= '9'; }

    /** Comprueba que parece un indicativo normal (como callok de WSJT-X). */
    static boolean indicativoValido(String w) {
        int n = w.length();
        if (n < 3 || w.charAt(0) == 'Q') return false;
        int i0 = -1;
        for (int i = n - 1; i >= 0; i--) if (esCifra(w.charAt(i))) { i0 = i; break; }
        if (i0 != 1 && i0 != 2) return false;
        String pfx = w.substring(0, i0), sfx = w.substring(i0 + 1);
        int nlp = 0;
        for (char c : pfx.toCharArray()) if (esLetra(c)) nlp++;
        if (nlp == 0) return false;
        for (char c : sfx.toCharArray()) if (!esLetra(c)) return false;
        return true;
    }

    /** Resultado de unpack28: indicativo (o CQ...) y si ha ido bien. */
    private record R28(String txt, boolean ok) { }

    private R28 unpack28(long n28) {
        if (n28 < NTOKENS) {
            if (n28 == 0) return new R28("DE", true);
            if (n28 == 1) return new R28("QRZ", true);
            if (n28 == 2) return new R28("CQ", true);
            if (n28 <= 1002) return new R28(String.format("CQ_%03d", n28 - 3), true);
            if (n28 <= 532443) {
                int n = (int) (n28 - 1003);
                int i1 = n / (27 * 27 * 27); n -= 27 * 27 * 27 * i1;
                int i2 = n / (27 * 27); n -= 27 * 27 * i2;
                int i3 = n / 27; int i4 = n - 27 * i3;
                String s = ("" + A4.charAt(i1) + A4.charAt(i2) + A4.charAt(i3) + A4.charAt(i4)).trim();
                return new R28("CQ_" + s, true);
            }
            return new R28("QU1RK", false);
        }
        long n = n28 - NTOKENS;
        if (n < MAX22) return new R28(de22((int) n), true);
        n -= MAX22;
        int i1 = (int) (n / (36L * 10 * 27 * 27 * 27)); n -= 36L * 10 * 27 * 27 * 27 * i1;
        int i2 = (int) (n / (10 * 27 * 27 * 27)); n -= 10L * 27 * 27 * 27 * i2;
        int i3 = (int) (n / (27 * 27 * 27)); n -= 27L * 27 * 27 * i3;
        int i4 = (int) (n / (27 * 27)); n -= 27L * 27 * i4;
        int i5 = (int) (n / 27); int i6 = (int) (n - 27 * i5);
        if (i1 > 36 || i2 > 35 || i3 > 9 || i4 > 26 || i5 > 26 || i6 > 26) return new R28("QU1RK", false);
        String c = ("" + A1.charAt(i1) + A2.charAt(i2) + A3.charAt(i3) + A4.charAt(i4) + A4.charAt(i5) + A4.charAt(i6)).trim();
        if (c.contains(" ") || !indicativoValido(c)) return new R28("QU1RK", false);
        // Prefijos especiales: 3D0XYZ -> 3DA0XYZ, QA0XYZ -> 3XA0XYZ (como hace WSJT-X al empaquetar)
        if (c.startsWith("3D0") && c.length() > 3) c = "3DA0" + c.substring(3);
        else if (c.length() > 1 && c.charAt(0) == 'Q' && esLetra(c.charAt(1))) c = "3X" + c.substring(1);
        return new R28(c, true);
    }

    private static String grid4(long n) {
        int j1 = (int) (n / (18 * 10 * 10)); n -= j1 * 18 * 10 * 10L;
        int j2 = (int) (n / 100); n -= j2 * 100L;
        int j3 = (int) (n / 10); int j4 = (int) (n - j3 * 10);
        if (j1 > 17 || j2 > 17 || j3 > 9 || j4 > 9 || j1 < 0) return null;
        return "" + (char) ('A' + j1) + (char) ('A' + j2) + (char) ('0' + j3) + (char) ('0' + j4);
    }

    private static String grid6(long n, int div) {
        // div = 24 (to_grid6) o 25 (to_grid, el último par puede faltar)
        long d = div;
        int j1 = (int) (n / (18 * 10 * 10 * d * d)); n -= j1 * 18L * 10 * 10 * d * d;
        int j2 = (int) (n / (10 * 10 * d * d)); n -= j2 * 10L * 10 * d * d;
        int j3 = (int) (n / (10 * d * d)); n -= j3 * 10L * d * d;
        int j4 = (int) (n / (d * d)); n -= j4 * d * d;
        int j5 = (int) (n / d); int j6 = (int) (n - j5 * d);
        if (j1 > 17 || j2 > 17 || j3 > 9 || j4 > 9 || j5 > div - 1 || j6 > div - 1 || j1 < 0) return null;
        String g = "" + (char) ('A' + j1) + (char) ('A' + j2) + (char) ('0' + j3) + (char) ('0' + j4);
        if (div == 24 || j5 != 24 || j6 != 24) g += "" + (char) ('A' + j5) + (char) ('A' + j6);
        return g;
    }

    private static String texto13(int[] b) {
        // 71 bits -> 13 caracteres (base 42)
        java.math.BigInteger v = java.math.BigInteger.ZERO;
        for (int i = 0; i < 71; i++) v = v.shiftLeft(1).or(java.math.BigInteger.valueOf(b[i] & 1));
        char[] c = new char[13];
        java.math.BigInteger b42 = java.math.BigInteger.valueOf(42);
        for (int i = 12; i >= 0; i--) {
            java.math.BigInteger[] qr = v.divideAndRemainder(b42);
            c[i] = C42.charAt(qr[1].intValue());
            v = qr[0];
        }
        return new String(c);
    }

    /** Resultado de traducir 77 bits a texto. */
    public record Texto(String mensaje, boolean ok) { }

    /**
     * 77 bits (0/1) -> texto, como unpack77 de WSJT-X (nrx=1: mensaje recibido).
     */
    public Texto aTexto(int[] b) {
        int n3 = (int) leer(b, 72, 3), i3 = (int) leer(b, 75, 3);
        boolean ok = true;
        String msg;
        if (i3 == 0 && n3 == 0) {
            msg = texto13(b).trim();
            if (msg.isEmpty()) return new Texto("", false);
        } else if (i3 == 0 && n3 == 1) {   // DXpedition: K1ABC RR73; W9XYZ <KH1/KH7Z> -12
            long n28a = leer(b, 1, 28), n28b = leer(b, 29, 28);
            int n10 = (int) leer(b, 57, 10), n5 = (int) leer(b, 67, 5);
            int irpt = 2 * n5 - 30;
            String crpt = (irpt >= 0 ? "+" : "-") + String.format("%02d", Math.abs(irpt));
            R28 c1 = unpack28(n28a), c2 = unpack28(n28b);
            if (!c1.ok || n28a <= 2 || !c2.ok || n28b <= 2) ok = false;
            String c3 = de10(n10);
            if (!suIndicativo.isEmpty() && suIndicativo.length() >= 3 && suHash10 == n10) c3 = "<" + suIndicativo + ">";
            msg = c1.txt + " RR73; " + c2.txt + " " + c3 + " " + crpt;
        } else if (i3 == 0 && n3 == 2) {
            return new Texto("", false);
        } else if (i3 == 0 && (n3 == 3 || n3 == 4)) {   // ARRL Field Day
            long n28a = leer(b, 1, 28), n28b = leer(b, 29, 28);
            int ir = (int) leer(b, 57, 1), intx = (int) leer(b, 58, 4), ncl = (int) leer(b, 62, 3), isec = (int) leer(b, 65, 7);
            if (isec > SECCIONES.length || isec < 1) { ok = false; isec = 1; }
            R28 c1 = unpack28(n28a), c2 = unpack28(n28b);
            if (!c1.ok || n28a <= 2 || !c2.ok || n28b <= 2) ok = false;
            int ntx = intx + 1 + (n3 == 4 ? 16 : 0);
            String cntx = ntx + "" + (char) ('A' + ncl);
            msg = c1.txt + " " + c2.txt + (ir == 1 ? " R " : " ") + cntx + " " + SECCIONES[isec - 1];
        } else if (i3 == 0 && n3 == 5) {   // telemetría
            long a = leer(b, 1, 23), c = leer(b, 24, 24), d = leer(b, 48, 24);
            String s = String.format("%06X%06X%06X", a, c, d).replaceFirst("^0+", "");
            msg = s;
        } else if (i3 == 0 && n3 == 6) {   // WSPR en FT8 (balizas)
            int j48 = b[47], j49 = b[48], j50 = b[49];
            int itype = j50 == 1 ? 2 : (j49 == 0 ? 1 : (j48 == 0 ? 3 : -1));
            if (itype == 1) {
                long n28 = leer(b, 1, 28); long ig = leer(b, 29, 15); int idbm = (int) Math.round(leer(b, 44, 5) * 10.0 / 3.0);
                R28 c1 = unpack28(n28); String g = grid4(ig);
                if (!c1.ok || g == null || idbm < 0 || idbm > 60) ok = false;
                msg = c1.txt + " " + (g == null ? "" : g) + " " + idbm;
            } else if (itype == 3) {
                long n22 = leer(b, 1, 22); long ig6 = leer(b, 23, 25);
                R28 c1 = unpack28(n22 + NTOKENS); String g = grid6(ig6, 25);
                if (!c1.ok || g == null) ok = false;
                msg = c1.txt + " " + (g == null ? "" : g);
            } else {
                long n28 = leer(b, 1, 28); int idbm = (int) Math.round(leer(b, 45, 5) * 10.0 / 3.0);
                R28 c1 = unpack28(n28);
                if (!c1.ok) ok = false;
                msg = c1.txt + " " + idbm;
            }
        } else if (i3 == 0) {
            return new Texto("", false);
        } else if (i3 == 1 || i3 == 2) {   // mensaje normal
            long n28a = leer(b, 1, 28); int ipa = (int) leer(b, 29, 1);
            long n28b = leer(b, 30, 28); int ipb = (int) leer(b, 58, 1);
            int ir = (int) leer(b, 59, 1); long ig = leer(b, 60, 15);
            R28 r1 = unpack28(n28a);
            String c1 = r1.txt;
            boolean ok1 = r1.ok;
            if (miHash22 >= 0 && miHash22 == n28a - NTOKENS) { c1 = "<" + miIndicativo + ">"; ok1 = true; }
            if (!ok1) ok = false;
            R28 r2 = unpack28(n28b);
            String c2 = r2.txt;
            if (!r2.ok) ok = false;
            if (c1.startsWith("CQ_")) c1 = "CQ " + c1.substring(3);
            if (!c1.contains("<") && c1.length() >= 3 && !c1.startsWith("CQ") && !c1.equals("DE") && !c1.equals("QRZ")) {
                if (ipa == 1) c1 += i3 == 1 ? "/R" : "/P";
            }
            if (!c2.contains("<") && c2.length() >= 3) {
                if (ipb == 1) c2 += i3 == 1 ? "/R" : "/P";
                guardar(c2);
            }
            if (ig <= MAXGRID4) {
                String g = grid4(ig);
                if (g == null) ok = false;
                msg = c1 + " " + c2 + (ir == 1 ? " R " : " ") + (g == null ? "" : g);
                if (msg.startsWith("CQ ") && ir == 1) ok = false;
            } else {
                int irpt = (int) (ig - MAXGRID4);
                msg = c1 + " " + c2;
                if (irpt == 2) msg += " RRR";
                else if (irpt == 3) msg += " RR73";
                else if (irpt == 4) msg += " 73";
                else if (irpt >= 5) {
                    int isnr = irpt - 35;
                    if (isnr > 50) isnr -= 101;
                    String crpt = (isnr >= 0 ? "+" : "-") + String.format("%02d", Math.abs(isnr));
                    msg += (ir == 1 ? " R" : " ") + crpt;
                }
                if (msg.startsWith("CQ ") && irpt >= 2) ok = false;
            }
        } else if (i3 == 3) {   // ARRL RTTY Roundup
            int itu = (int) leer(b, 1, 1);
            long n28a = leer(b, 2, 28), n28b = leer(b, 30, 28);
            int ir = (int) leer(b, 58, 1), irpt = (int) leer(b, 59, 3), nexch = (int) leer(b, 62, 13);
            String crpt = "5" + (irpt + 2) + "9";
            R28 c1 = unpack28(n28a), c2 = unpack28(n28b);
            if (!c1.ok || !c2.ok) ok = false;
            String exch = null;
            if (nexch > 8000) { int im = nexch - 8000; if (im >= 1 && im <= 171) exch = ESTADOS[im - 1]; }
            else if (nexch >= 1 && nexch <= 7999) exch = String.format("%04d", nexch);
            if (exch == null) return new Texto("", false);
            msg = (itu == 1 ? "TU; " : "") + c1.txt + " " + c2.txt + (ir == 1 ? " R " : " ") + crpt + " " + exch;
        } else if (i3 == 4) {   // un indicativo no normal y otro resumido
            int n12 = (int) leer(b, 1, 12);
            long n58 = leer(b, 13, 58);
            int iflip = (int) leer(b, 71, 1), nrpt = (int) leer(b, 72, 2), icq = (int) leer(b, 74, 1);
            char[] c11 = new char[11];
            for (int i = 10; i >= 0; i--) { c11[i] = C38.charAt((int) (n58 % 38)); n58 /= 38; }
            String llano = new String(c11).trim();
            String c3 = de12(n12);
            String c1, c2;
            if (iflip == 0) {
                c1 = c3; c2 = llano;
                guardar(c2);
                if (miHash12 >= 0 && n12 == miHash12 && (c1.contains("<...>") || c2.equals(suIndicativo))) c1 = "<" + miIndicativo + ">";
            } else {
                c1 = llano; c2 = c3;
                guardar(c1);
            }
            if (icq == 0) {
                msg = c1 + " " + c2 + (nrpt == 1 ? " RRR" : nrpt == 2 ? " RR73" : nrpt == 3 ? " 73" : "");
            } else msg = "CQ " + c2;
        } else if (i3 == 5) {   // EU VHF con resúmenes
            int n12 = (int) leer(b, 1, 12); int n22 = (int) leer(b, 13, 22);
            int ir = (int) leer(b, 35, 1), irpt = (int) leer(b, 36, 3), iser = (int) leer(b, 39, 11);
            long ig6 = leer(b, 50, 25);
            if (ig6 > 18662399) return new Texto("", false);
            String c1 = de12(n12);
            if (n12 == miHash12 && miHash12 >= 0) c1 = "<" + miIndicativo + ">";
            String c2 = de22(n22);
            String g = grid6(ig6, 24);
            if (g == null) ok = false;
            msg = c1 + " " + c2 + (ir == 1 ? " R " : " ") + String.format("%2d%04d", 52 + irpt, iser) + " " + (g == null ? "" : g);
        } else {
            return new Texto("", false);
        }
        if (msg.startsWith("CQ <")) ok = false;
        return new Texto(msg.trim().replaceAll(" +", " "), ok);
    }

    // =================== Texto -> bits (mensajes normales, no normales y texto libre) ===================

    /** Empaqueta un indicativo o CQ/DE/QRZ en 28 bits (pack28). -1 si no se puede. */
    long pack28(String c) {
        if (c.equals("DE")) return 0;
        if (c.equals("QRZ")) return 1;
        if (c.equals("CQ")) return 2;
        if (c.startsWith("CQ_") || c.startsWith("CQ ")) {
            String r = c.substring(3).trim();
            int n = r.length(), nlet = 0, nnum = 0;
            for (char ch : r.toCharArray()) { if (esLetra(ch)) nlet++; if (esCifra(ch)) nnum++; }
            if (nnum == 3 && nlet == 0 && n == 3) return 3 + Integer.parseInt(r);
            if (nlet >= 1 && nlet <= 4 && nnum == 0 && n == nlet) {
                String c4 = String.format("%4s", r);
                int m = 0;
                for (int i = 0; i < 4; i++) { char ch = c4.charAt(i); m = 27 * m + (esLetra(ch) ? ch - 'A' + 1 : 0); }
                return 3 + 1000 + m;
            }
            return -1;
        }
        if (c.startsWith("<")) {
            int k = c.indexOf('>');
            String cc = c.substring(1, k < 0 ? c.length() : k);
            guardar(cc);
            return NTOKENS + hash(cc, 22);
        }
        String cs = c;
        if (cs.startsWith("3DA0") && cs.length() > 4) cs = "3D0" + cs.substring(4);
        else if (cs.startsWith("3X") && cs.length() > 2 && esLetra(cs.charAt(2))) cs = "Q" + cs.substring(2);
        int n = cs.length(), iarea = -1;
        for (int i = n - 1; i >= 1; i--) if (esCifra(cs.charAt(i))) { iarea = i + 1; break; }
        int npdig = 0, nplet = 0, nslet = 0;
        for (int i = 0; i < iarea - 1; i++) { if (esCifra(cs.charAt(i))) npdig++; if (esLetra(cs.charAt(i))) nplet++; }
        for (int i = Math.max(iarea, 0); i < n; i++) if (esLetra(cs.charAt(i))) nslet++;
        guardar(c);
        if (iarea < 2 || iarea > 3 || nplet == 0 || npdig >= iarea - 1 || nslet > 3 || n > 6) {
            return NTOKENS + hash(c, 22);
        }
        String six = iarea == 2 ? " " + cs : cs;
        six = String.format("%-6s", six);
        int i1 = A1.indexOf(six.charAt(0)), i2 = A2.indexOf(six.charAt(1)), i3 = A3.indexOf(six.charAt(2));
        int i4 = A4.indexOf(six.charAt(3)), i5 = A4.indexOf(six.charAt(4)), i6 = A4.indexOf(six.charAt(5));
        if (i1 < 0 || i2 < 0 || i3 < 0 || i4 < 0 || i5 < 0 || i6 < 0) return NTOKENS + hash(c, 22);
        long n28 = 36L * 10 * 27 * 27 * 27 * i1 + 10L * 27 * 27 * 27 * i2 + 27L * 27 * 27 * i3 + 27L * 27 * i4 + 27L * i5 + i6;
        return (n28 + NTOKENS + MAX22) & ((1L << 28) - 1);
    }

    private static void escribir(int[] b, int desde, int n, long v) {
        for (int i = n - 1; i >= 0; i--) { b[desde - 1 + i] = (int) (v & 1); v >>= 1; }
    }

    private static boolean esGrid4(String g) {
        return g.length() == 4 && g.charAt(0) >= 'A' && g.charAt(0) <= 'R' && g.charAt(1) >= 'A' && g.charAt(1) <= 'R'
                && esCifra(g.charAt(2)) && esCifra(g.charAt(3));
    }

    /** Indicativo "base" válido para un mensaje normal (sin /P ni /R). */
    private static String base(String w) {
        if (w.endsWith("/P") || w.endsWith("/R")) w = w.substring(0, w.length() - 2);
        return w;
    }

    /**
     * Texto -> 77 bits (0/1). Intenta mensaje normal, luego indicativo no normal y si no, texto libre.
     * Devuelve null si no se puede enviar.
     */
    public int[] aBits(String texto) {
        String t = texto.trim().toUpperCase().replaceAll(" +", " ");
        String[] w = t.split(" ");
        java.util.List<String> p = new java.util.ArrayList<>(java.util.Arrays.asList(w));
        // "CQ DX EA4XYZ IN80" -> "CQ_DX EA4XYZ IN80"
        if (p.size() >= 3 && p.get(0).equals("CQ") && (indicativoValido(base(p.get(2))) || p.get(2).startsWith("<"))
                && !indicativoValido(base(p.get(1)))) {
            p.set(0, "CQ_" + p.get(1));
            p.remove(1);
        }
        int[] r = expedicion(t);
        if (r != null) return r;
        r = euVhf(p);
        if (r != null) return r;
        r = fieldDay(p);
        if (r != null) return r;
        r = rttyRu(p);
        if (r != null) return r;
        r = normal(p);
        if (r != null) return r;
        r = noNormal(p);
        if (r != null) return r;
        return textoLibre(t);
    }

    private int[] normal(java.util.List<String> w) {
        int nw = w.size();
        if (nw < 2 || nw > 4) return null;
        String w1 = w.get(0), w2 = w.get(1);
        boolean ok1 = indicativoValido(base(w1)) || w1.equals("DE") || w1.startsWith("CQ") || w1.equals("QRZ")
                || (w1.startsWith("<") && w1.indexOf('>') >= 4);
        boolean ok2 = indicativoValido(base(w2)) || (w2.startsWith("<") && w2.indexOf('>') >= 4);
        if (!ok1 || !ok2) return null;
        if (base(w1).length() > 6 && !w1.startsWith("<") && !w1.startsWith("CQ")) return null;
        if (base(w2).length() > 6 && !w2.startsWith("<")) return null;
        if (w1.startsWith("<") && w2.contains("/")) return null;
        if (w2.startsWith("<") && w1.contains("/")) return null;
        int ir = 0, irpt = 1;
        long igrid4;
        String ult = w.get(nw - 1);
        if (nw == 2) igrid4 = MAXGRID4 + 1;
        else {
            if (nw == 4 && !w.get(2).equals("R")) return null;
            if (esGrid4(ult)) {
                ir = nw == 4 ? 1 : 0;
                igrid4 = (ult.charAt(0) - 'A') * 1800L + (ult.charAt(1) - 'A') * 100L + (ult.charAt(2) - '0') * 10L + (ult.charAt(3) - '0');
            } else {
                if (nw == 4) return null;
                try {
                    if (ult.startsWith("+") || ult.startsWith("-")) {
                        irpt = Integer.parseInt(ult.replace("+", ""));
                        if (irpt >= -50 && irpt <= -31) irpt += 101;
                        irpt += 35;
                    } else if (ult.startsWith("R+") || ult.startsWith("R-")) {
                        ir = 1;
                        irpt = Integer.parseInt(ult.substring(1).replace("+", ""));
                        if (irpt >= -50 && irpt <= -31) irpt += 101;
                        irpt += 35;
                    } else if (ult.equals("RRR")) irpt = 2;
                    else if (ult.equals("RR73")) irpt = 3;
                    else if (ult.equals("73")) irpt = 4;
                    else return null;
                } catch (NumberFormatException e) { return null; }
                igrid4 = MAXGRID4 + irpt;
            }
        }
        int i3 = (w1.endsWith("/P") || w2.endsWith("/P")) ? 2 : 1;
        long n28a = pack28(w1.startsWith("CQ") || w1.startsWith("<") ? w1 : base(w1));
        long n28b = pack28(w2.startsWith("<") ? w2 : base(w2));
        if (n28a < 0 || n28b < 0) return null;
        int ipa = (w1.endsWith("/P") || w1.endsWith("/R")) ? 1 : 0, ipb = (w2.endsWith("/P") || w2.endsWith("/R")) ? 1 : 0;
        int[] b = new int[77];
        escribir(b, 1, 28, n28a); escribir(b, 29, 1, ipa);
        escribir(b, 30, 28, n28b); escribir(b, 58, 1, ipb);
        escribir(b, 59, 1, ir); escribir(b, 60, 15, igrid4); escribir(b, 75, 3, i3);
        return b;
    }

    /** Indicativo normal (sin barras raras) que cabe en 28 bits, o -1. */
    private long llamada28(String w) {
        if (w.startsWith("<") || w.startsWith("CQ") || !indicativoValido(base(w)) || base(w).length() > 6) return -1;
        return pack28(base(w));
    }

    /** Expedición (Fox): "K1ABC RR73; W9XYZ <KH1ABC> -12" (i3=0, n3=1). El informe va en pasos de 2 dB. */
    private int[] expedicion(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([A-Z0-9/]+) RR73; ([A-Z0-9/]+) <([A-Z0-9/]+)> ([+-]\\d{1,2})$").matcher(t);
        if (!m.matches()) return null;
        long a = llamada28(m.group(1)), b = llamada28(m.group(2));
        if (a < 0 || b < 0) return null;
        int rpt;
        try { rpt = Integer.parseInt(m.group(4).replace("+", "")); } catch (NumberFormatException e) { return null; }
        int n5 = Math.max(0, Math.min(31, (int) Math.round((rpt + 30) / 2.0)));
        String fox = m.group(3);
        guardar(fox);
        int[] r = new int[77];
        escribir(r, 1, 28, a); escribir(r, 29, 28, b); escribir(r, 57, 10, hash(fox, 10)); escribir(r, 67, 5, n5);
        escribir(r, 72, 3, 1); escribir(r, 75, 3, 0);
        return r;
    }

    /** ARRL Field Day: "W9XYZ K1ABC R 2B EMA" (i3=0, n3=3 ó 4). */
    private int[] fieldDay(java.util.List<String> w) {
        int nw = w.size();
        if (nw != 4 && nw != 5) return null;
        boolean r = nw == 5;
        if (r && !w.get(2).equals("R")) return null;
        String cl = w.get(nw - 2), sec = w.get(nw - 1);
        if (!cl.matches("\\d{1,2}[A-F]")) return null;
        int isec = java.util.Arrays.asList(SECCIONES).indexOf(sec) + 1;
        if (isec < 1) return null;
        int ntx = Integer.parseInt(cl.substring(0, cl.length() - 1));
        if (ntx < 1 || ntx > 32) return null;
        long a = llamada28(w.get(0)), b = llamada28(w.get(1));
        if (a < 0 || b < 0) return null;
        int n3 = ntx > 16 ? 4 : 3, intx = ntx - 1 - (ntx > 16 ? 16 : 0);
        int[] x = new int[77];
        escribir(x, 1, 28, a); escribir(x, 29, 28, b); escribir(x, 57, 1, r ? 1 : 0); escribir(x, 58, 4, intx);
        escribir(x, 62, 3, cl.charAt(cl.length() - 1) - 'A'); escribir(x, 65, 7, isec); escribir(x, 72, 3, n3); escribir(x, 75, 3, 0);
        return x;
    }

    /** ARRL RTTY Roundup: "[TU;] W9XYZ K1ABC [R] 579 MA" o con número de serie "579 0013" (i3=3). */
    private int[] rttyRu(java.util.List<String> w0) {
        java.util.List<String> w = new java.util.ArrayList<>(w0);
        boolean tu = !w.isEmpty() && w.get(0).equals("TU;");
        if (tu) w.remove(0);
        int nw = w.size();
        if (nw != 4 && nw != 5) return null;
        boolean r = nw == 5;
        if (r && !w.get(2).equals("R")) return null;
        String rst = w.get(nw - 2), ex = w.get(nw - 1);
        if (!rst.matches("5[2-9]9")) return null;
        int nexch;
        if (ex.matches("\\d{1,4}")) { nexch = Integer.parseInt(ex); if (nexch < 1 || nexch > 7999) return null; }
        else {
            int i = java.util.Arrays.asList(ESTADOS).indexOf(ex);
            if (i < 0 || ex.startsWith("X")) return null;
            nexch = 8000 + i + 1;
        }
        long a = llamada28(w.get(0)), b = llamada28(w.get(1));
        if (a < 0 || b < 0) return null;
        int[] x = new int[77];
        escribir(x, 1, 1, tu ? 1 : 0); escribir(x, 2, 28, a); escribir(x, 30, 28, b); escribir(x, 58, 1, r ? 1 : 0);
        escribir(x, 59, 3, rst.charAt(1) - '0' - 2); escribir(x, 62, 13, nexch); escribir(x, 75, 3, 3);
        return x;
    }

    /** EU VHF: "<PA3XYZ> <G4ABC/P> R 590003 IO91NP" (i3=5; los dos indicativos van resumidos). */
    private int[] euVhf(java.util.List<String> w) {
        int nw = w.size();
        if (nw != 4 && nw != 5) return null;
        boolean r = nw == 5;
        if (r && !w.get(2).equals("R")) return null;
        String c1 = w.get(0), c2 = w.get(1), ex = w.get(nw - 2), g = w.get(nw - 1);
        if (!c1.matches("<[A-Z0-9/]{3,11}>") || !c2.matches("<[A-Z0-9/]{3,11}>")) return null;
        if (!ex.matches("5[2-9]\\d{4}") || !g.matches("[A-R]{2}\\d{2}[A-X]{2}")) return null;
        int iser = Integer.parseInt(ex.substring(2));
        if (iser > 2047) return null;
        c1 = c1.substring(1, c1.length() - 1); c2 = c2.substring(1, c2.length() - 1);
        guardar(c1); guardar(c2);
        long ig6 = (((((g.charAt(0) - 'A') * 18L + (g.charAt(1) - 'A')) * 10 + (g.charAt(2) - '0')) * 10 + (g.charAt(3) - '0')) * 24
                + (g.charAt(4) - 'A')) * 24 + (g.charAt(5) - 'A');
        int[] x = new int[77];
        escribir(x, 1, 12, hash(c1, 12)); escribir(x, 13, 22, hash(c2, 22)); escribir(x, 35, 1, r ? 1 : 0);
        escribir(x, 36, 3, ex.charAt(1) - '2'); escribir(x, 39, 11, iser); escribir(x, 50, 25, ig6); escribir(x, 75, 3, 5);
        return x;
    }

    private int[] noNormal(java.util.List<String> w) {
        int nw = w.size();
        if (nw < 2 || nw > 3) return null;
        String c1 = w.get(0), c2 = w.get(1);
        boolean icq = c1.equals("CQ");
        if (nw == 3 && icq) return null;
        int nrpt = 0;
        if (nw == 3) {
            String x = w.get(2);
            if (x.equals("RRR")) nrpt = 1; else if (x.equals("RR73")) nrpt = 2; else if (x.equals("73")) nrpt = 3; else return null;
        }
        int iflip = 0;
        String llano, resumido;
        if (icq) { llano = c2; resumido = null; }
        else if (c1.startsWith("<")) { iflip = 0; resumido = c1; llano = c2; }
        else if (c2.startsWith("<")) { iflip = 1; resumido = c2; llano = c1; }
        else {   // ninguno entre <>: va en claro el que no es normal
            if (!indicativoValido(base(c2))) { iflip = 0; resumido = c1; llano = c2; }
            else if (!indicativoValido(base(c1))) { iflip = 1; resumido = c2; llano = c1; }
            else return null;
        }
        llano = llano.replace("<", "").replace(">", "");
        if (llano.length() < 3 || llano.length() > 11) return null;
        long n58 = 0;
        for (int i = 0; i < 11; i++) {
            char ch = i < llano.length() ? llano.charAt(i) : ' ';
            int j = C38.indexOf(ch);
            if (j < 0) return null;
            n58 = 38 * n58 + j;
        }
        // alineado a la derecha como WSJT-X
        n58 = 0;
        String der = String.format("%11s", llano);
        for (int i = 0; i < 11; i++) n58 = 38 * n58 + C38.indexOf(der.charAt(i));
        guardar(llano);
        int n12 = 0;
        if (resumido != null) {
            String r = resumido.replace("<", "").replace(">", "");
            guardar(r);
            n12 = hash(r, 12);
        }
        int[] b = new int[77];
        escribir(b, 1, 12, n12); escribir(b, 13, 58, n58);
        escribir(b, 71, 1, iflip); escribir(b, 72, 2, nrpt); escribir(b, 74, 1, icq ? 1 : 0); escribir(b, 75, 3, 4);
        return b;
    }

    private static int[] textoLibre(String t) {
        if (t.length() > 13) t = t.substring(0, 13);
        String w = String.format("%13s", t);
        java.math.BigInteger v = java.math.BigInteger.ZERO, b42 = java.math.BigInteger.valueOf(42);
        for (int i = 0; i < 13; i++) {
            int j = C42.indexOf(w.charAt(i));
            if (j < 0) j = 0;
            v = v.multiply(b42).add(java.math.BigInteger.valueOf(j));
        }
        int[] b = new int[77];
        for (int i = 70; i >= 0; i--) { b[i] = v.testBit(70 - i) ? 1 : 0; }
        return b;   // i3=0, n3=0
    }
}
