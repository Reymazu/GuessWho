package com.guesswho;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/** Mengubah file gambar menjadi item map 128x128 yang bisa dipasang di item frame. */
final class MapImages {

    private MapImages() {
    }

    static ItemStack create(World world, File file) throws IOException {
        BufferedImage src = ImageIO.read(file);
        if (src == null) {
            throw new IOException("Format gambar tidak didukung: " + file.getName());
        }

        BufferedImage out = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 128, 128);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double scale = Math.min(128.0 / src.getWidth(), 128.0 / src.getHeight());
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        g.drawImage(src, (128 - w) / 2, (128 - h) / 2, w, h, null);
        g.dispose();

        MapView view = Bukkit.createMap(world);
        for (MapRenderer r : new ArrayList<>(view.getRenderers())) {
            view.removeRenderer(r);
        }
        view.addRenderer(new StaticRenderer(out));
        view.setTrackingPosition(false);

        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        item.setItemMeta(meta);
        return item;
    }

    private static final class StaticRenderer extends MapRenderer {
        private final BufferedImage image;
        private boolean drawn;

        StaticRenderer(BufferedImage image) {
            super(false); // non-contextual: digambar sekali untuk semua pemain
            this.image = image;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (drawn) {
                return;
            }
            canvas.drawImage(0, 0, image);
            drawn = true;
        }
    }
}
