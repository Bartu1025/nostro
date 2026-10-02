package nostro.core;

/** Money is a long in minor units; the currency carries the scale. There is no floating point anywhere in this package (enforced by ArchRules). */
public enum Ccy {
    GBP(2), EUR(2), USD(2), JPY(0), KWD(3);

    public final int scale;

    Ccy(int scale) { this.scale = scale; }

    /** 123456 GBP -> "1234.56"; 123456 JPY -> "123456"; 123456 KWD -> "123.456". Display only. */
    public String fmt(long minor) {
        if (scale == 0) return Long.toString(minor);
        long p = 1;
        for (int i = 0; i < scale; i++) p *= 10;
        long abs = Math.abs(minor);
        return (minor < 0 ? "-" : "") + (abs / p) + "." + String.format(java.util.Locale.ROOT, "%0" + scale + "d", abs % p);
    }
}
