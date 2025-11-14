import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.images.ImageData;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.scripting.QP;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Paths;
import java.util.ServiceLoader;
import java.util.Collection;
import java.util.Map;
import java.util.HashMap;
import java.io.File;
import java.io.IOException;
import java.net.URI;

/**
 * Script pour générer des masques et extraire les régions d'image correspondantes
 * à partir des annotations QuPath.
 */
public class GenerateMasks {

    public static void main(String[] args) throws Exception {
        
        QP.getCoreClasses();
        ImageServerProvider.setServiceLoader(ServiceLoader.load(ImageServerBuilder.class));

        // Créer les répertoires de sortie
        String maskOutputDir = "mask_output/masks";
        String imageOutputDir = "mask_output/images";
        new File(maskOutputDir).mkdirs();
        new File(imageOutputDir).mkdirs();

        Project<BufferedImage> project = ProjectIO.loadProject(
            Paths.get("/Volumes/Elements/QuPath 3 dataset entrainement en cours/project.qpproj").toFile(),
            BufferedImage.class
        );

        
        // Section de correction des chemins
        System.out.println("Vérification et correction des chemins d'images...");
        String badPrefix = "/F:/";
        String goodPrefix = "/Volumes/Elements/";

        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            Collection<URI> uris;
            try {
                uris = entry.getURIs();
            } catch (IOException e) {
                System.err.println("  Image : " + entry.getImageName() + " (Impossible de lire les URIs)");
                e.printStackTrace();
                continue;
            }

            if (uris == null || uris.isEmpty()) {
                continue;
            }

            Map<URI, URI> replacements = new HashMap<>();
            for (URI uri : uris) {
                String path = uri.getPath();
                if (path != null && path.startsWith(badPrefix)) {
                    String newPath = path.replaceFirst(badPrefix, goodPrefix).replace("\\", "/");
                    URI newUri = new File(newPath).toURI();
                    replacements.put(uri, newUri);
                }
            }

            if (!replacements.isEmpty()) {
                try {
                    entry.updateURIs(replacements);
                } catch (IOException e) {
                    System.err.println("  Image : " + entry.getImageName() + " (Échec lors de la mise à jour des URIs)");
                    e.printStackTrace();
                }
            }
        }
        System.out.println("Correction des chemins terminée.\n");

        // Traitement des images et génération des masques
        int totalMasksGenerated = 0;
        
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            System.out.println("Traitement de: " + entry.getImageName());

            try (ImageData<BufferedImage> imageData = entry.readImageData()) {
                PathObjectHierarchy hierarchy = imageData.getHierarchy();
                var annotations = hierarchy != null ? hierarchy.getAnnotationObjects() : null;

                if (annotations == null || annotations.isEmpty()) {
                    System.out.println("  Aucune annotation trouvée.");
                    continue;
                }

                ImageServer<BufferedImage> server;
                try {
                    server = imageData.getServer();
                } catch (Exception e) {
                    System.err.println("  Impossible de charger l'image: " + e.getMessage());
                    continue;
                }

                // Traiter chaque annotation
                int annotationIndex = 0;
                for (var annotation : annotations) {
                    try {
                        ROI roi = annotation.getROI();
                        
                        // Calculer le bounding box de l'annotation
                        int x = (int) roi.getBoundsX();
                        int y = (int) roi.getBoundsY();
                        int width = (int) roi.getBoundsWidth();
                        int height = (int) roi.getBoundsHeight();

                        // Vérifier que les dimensions sont valides
                        if (width <= 0 || height <= 0) {
                            System.out.println("  Annotation " + annotationIndex + " ignorée (dimensions invalides)");
                            annotationIndex++;
                            continue;
                        }

                        // S'assurer que la région est dans les limites de l'image
                        x = Math.max(0, Math.min(x, server.getWidth() - 1));
                        y = Math.max(0, Math.min(y, server.getHeight() - 1));
                        width = Math.min(width, server.getWidth() - x);
                        height = Math.min(height, server.getHeight() - y);

                        // Créer une requête de région avec un downsample de 1 (pleine résolution)
                        // Ajuster le downsample si nécessaire pour de grandes régions
                        double downsample = 1.0;
                        if (width > 4096 || height > 4096) {
                            downsample = Math.max(width / 4096.0, height / 4096.0);
                        }

                        RegionRequest request = RegionRequest.createInstance(
                            server.getPath(),
                            downsample,
                            x, y, width, height
                        );

                        // Extraire la région de l'image
                        BufferedImage regionImage = server.readRegion(request);

                        // Créer le masque
                        int maskWidth = regionImage.getWidth();
                        int maskHeight = regionImage.getHeight();
                        BufferedImage mask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_BYTE_GRAY);
                        Graphics2D g2d = mask.createGraphics();
                        g2d.setColor(Color.BLACK);
                        g2d.fillRect(0, 0, maskWidth, maskHeight);

                        // Convertir le ROI en forme pour le dessiner sur le masque
                        g2d.setColor(Color.WHITE);
                        Shape shape = roi.getShape();
                        
                        // Translater et mettre à l'échelle la forme pour correspondre à la région extraite
                        java.awt.geom.AffineTransform transform = new java.awt.geom.AffineTransform();
                        transform.translate(-x, -y);
                        transform.scale(1.0 / downsample, 1.0 / downsample);
                        shape = transform.createTransformedShape(shape);
                        
                        g2d.fill(shape);
                        g2d.dispose();

                        // Générer les noms de fichiers
                        String baseName = entry.getImageName().replaceAll("\\.[^.]+$", ""); // Enlever l'extension
                        baseName = baseName.replaceAll("[^a-zA-Z0-9_-]", "_"); // Nettoyer le nom
                        
                        String classification = annotation.getPathClass() != null ? 
                            annotation.getPathClass().toString() : "unclassified";
                        classification = classification.replaceAll("[^a-zA-Z0-9_-]", "_");

                        String outputBaseName = String.format("%s_annot%d_%s", baseName, annotationIndex, classification);

                        // Sauvegarder le masque
                        File maskFile = new File(maskOutputDir, outputBaseName + "_mask.png");
                        ImageIO.write(mask, "PNG", maskFile);

                        // Sauvegarder l'image extraite
                        File imageFile = new File(imageOutputDir, outputBaseName + "_image.png");
                        ImageIO.write(regionImage, "PNG", imageFile);

                        System.out.println("  ✓ Annotation " + annotationIndex + " : " + 
                            outputBaseName + " (" + maskWidth + "x" + maskHeight + ")");
                        
                        totalMasksGenerated++;
                        annotationIndex++;

                    } catch (Exception e) {
                        System.err.println("  ✗ Erreur lors du traitement de l'annotation " + annotationIndex + ": " + e.getMessage());
                        e.printStackTrace();
                        annotationIndex++;
                    }
                }

            } catch (IOException e) {
                System.err.println("  Impossible de lire les données d'image: " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("\n==============================================");
        System.out.println("TERMINÉ ! " + totalMasksGenerated + " masques générés.");
        System.out.println("Masques : " + maskOutputDir);
        System.out.println("Images  : " + imageOutputDir);
        System.out.println("==============================================");
    }
}
