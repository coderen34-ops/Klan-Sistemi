package me.ailesistemi.savas;

import org.bukkit.Location;

public class Arena {
    public final String isim;
    public Location pos1, pos2, spawn1, spawn2;
    public transient Savas kullanan; // Şu an bu arenada süren savaş (kalıcı değil)

    public Arena(String isim) {
        this.isim = isim;
    }

    public boolean hazir() {
        return pos1 != null && pos2 != null && spawn1 != null && spawn2 != null
                && pos1.getWorld() != null && pos1.getWorld().equals(pos2.getWorld())
                && spawn1.getWorld() != null && spawn2.getWorld() != null;
    }

    public String eksikler() {
        StringBuilder sb = new StringBuilder();
        if (pos1 == null) sb.append("pos1 ");
        if (pos2 == null) sb.append("pos2 ");
        if (spawn1 == null) sb.append("spawn1 ");
        if (spawn2 == null) sb.append("spawn2 ");
        return sb.toString().trim();
    }

    /** Konum arena sınırları içinde mi? (farklı dünya = dışarıda) */
    public boolean icinde(Location loc) {
        if (pos1 == null || pos2 == null || loc == null || loc.getWorld() == null || !loc.getWorld().equals(pos1.getWorld())) return false;
        return arada(loc.getX(), pos1.getX(), pos2.getX()) && arada(loc.getY(), pos1.getY(), pos2.getY()) && arada(loc.getZ(), pos1.getZ(), pos2.getZ());
    }

    private static boolean arada(double d, double a, double b) {
        return d >= Math.min(a, b) && d <= Math.max(a, b) + 1;
    }
}
