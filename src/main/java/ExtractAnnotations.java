import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.images.ImageData;
import qupath.lib.objects.hierarchy.PathObjectHierarchy; 
import qupath.lib.io.GsonTools; 
import qupath.lib.images.servers.ImageServerBuilder; 
import qupath.lib.scripting.QP; 
import qupath.lib.images.servers.ImageServerProvider; 
import qupath.lib.images.servers.ImageServer;

import java.io.FileWriter; 
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.file.Paths;
import java.util.ServiceLoader; 
import java.util.Map;       
import java.util.List;      
import java.util.ArrayList; 
import java.util.HashMap;   
import java.util.Collection;
import java.io.File; 
import com.google.gson.Gson; 
import com.google.gson.GsonBuilder;

// Imports JTS pour la géométrie
import org.locationtech.jts.geom.Geometry; 
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.MultiPolygon; 

// Import pour la gestion des URI
import java.net.URI; 

// --- IMPORTS CORRIGÉS POUR LA CORRECTION DES CHEMINS ---

public class ExtractAnnotations {

    public static void main(String[] args) throws Exception {
        
        QP.getCoreClasses(); 
        ImageServerProvider.setServiceLoader(ServiceLoader.load(ImageServerBuilder.class));
        
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        
        String outputDirectory = "geojson_output";
        new File(outputDirectory).mkdirs();
        String outputFilename = Paths.get(outputDirectory, "toutes_les_annotations.geojson").toString();

        Map<String, Object> featureCollection = new HashMap<>();
        featureCollection.put("type", "FeatureCollection");
        List<Map<String, Object>> features = new ArrayList<>();
        featureCollection.put("features", features);

        Project<BufferedImage> project = ProjectIO.loadProject(
            Paths.get("/Volumes/Elements/QuPath 2 dataset test/project.qpproj").toFile(),
            BufferedImage.class
        );

        // --- SECTION DE CORRECTION DES CHEMINS (inchangée, mais les imports sont corrigés) ---
        System.out.println("Vérification et correction des chemins d'images...");
        String badPrefix = "/F:/"; // Le chemin Windows (F:\) tel que vu par Java
        String goodPrefix = "/Volumes/Elements/"; // Le chemin de remplacement sur macOS

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
                System.out.println("  Image : " + entry.getImageName() + " (Aucune URI à corriger)");
                continue;
            }

            Map<URI, URI> replacements = new HashMap<>();
            for (URI uri : uris) {
                String path = uri.getPath();
                if (path != null && path.startsWith(badPrefix)) {
                    String newPath = path.replaceFirst(badPrefix, goodPrefix).replace("\\", "/");

                    System.out.println("  Image : " + entry.getImageName());
                    System.out.println("    Chemin corrompu : " + path);
                    System.out.println("    Nouveau chemin : " + newPath);

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
        System.out.println("Correction des chemins terminée.");
        // --- FIN DE LA SECTION CORRIGÉE ---
        
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            System.out.println("Traitement de: " + entry.getImageName());

            try (ImageData<BufferedImage> imageData = entry.readImageData()) {
                PathObjectHierarchy hierarchy = imageData.getHierarchy();
                var annotations = hierarchy != null ? hierarchy.getAnnotationObjects() : null;

                if (annotations == null || annotations.isEmpty()) {
                    continue;
                }

                ImageServer<BufferedImage> server;
                try {
                    server = imageData.getServer();
                } catch (Exception e) {
                    System.err.println("  Image : " + entry.getImageName() + " (Impossible de charger l'image) : " + e.getMessage());
                    e.printStackTrace();
                    continue;
                }

                long imageWidth = server.getWidth();
                long imageHeight = server.getHeight();

                for (var annotation : annotations) {
                
                    Map<String, Object> properties = new HashMap<>();
                    properties.put("image_name", entry.getImageName());
                    if (annotation.getPathClass() != null) {
                        properties.put("classification", annotation.getPathClass().toString());
                    } else {
                        properties.put("classification", "(aucune)");
                    }

                    properties.put("image_width_base", imageWidth);
                    properties.put("image_height_base", imageHeight);

                    var roi = annotation.getROI();
                    Geometry jtsGeometry = roi.getGeometry();

                    Map<String, Object> geometry = convertJtsToGeoJson(jtsGeometry);

                    if (geometry != null) {
                        Map<String, Object> feature = new HashMap<>();
                        feature.put("type", "Feature");
                        feature.put("geometry", geometry);
                        feature.put("properties", properties);
                        features.add(feature);
                    }
                }
            } catch (IOException e) {
                System.err.println("  Image : " + entry.getImageName() + " (Impossible de lire les données d'image)");
                e.printStackTrace();
            }
        }

        if (!features.isEmpty()) {
            try (FileWriter writer = new FileWriter(outputFilename)) {
                gson.toJson(featureCollection, writer);
                System.out.println("\nSUCCÈS ! Fichier GeoJSON global écrit dans " + outputFilename);
            } catch (IOException e) {
                System.err.println("Erreur lors de l'écriture du fichier " + outputFilename);
                e.printStackTrace();
            }
        } else {
            System.out.println("\nAucune annotation trouvée dans le projet.");
        }
    } 

    private static Map<String, Object> convertJtsToGeoJson(Geometry geometry) {
        Map<String, Object> geoJsonGeometry = new HashMap<>();
        String type = geometry.getGeometryType();
        geoJsonGeometry.put("type", type);

        if (geometry instanceof Polygon) {
            Polygon polygon = (Polygon) geometry;
            List<List<double[]>> coordinates = new ArrayList<>();
            coordinates.add(getCoordinates(polygon.getExteriorRing().getCoordinates()));
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                coordinates.add(getCoordinates(polygon.getInteriorRingN(i).getCoordinates()));
            }
            geoJsonGeometry.put("coordinates", coordinates);
        
        } else if (geometry instanceof MultiPolygon) { 
            MultiPolygon multiPolygon = (MultiPolygon) geometry;
            List<List<List<double[]>>> allPolygons = new ArrayList<>();
            for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
                Polygon polygon = (Polygon) multiPolygon.getGeometryN(i);
                List<List<double[]>> polyCoordinates = new ArrayList<>();
                polyCoordinates.add(getCoordinates(polygon.getExteriorRing().getCoordinates()));
                for (int j = 0; j < polygon.getNumInteriorRing(); j++) {
                    polyCoordinates.add(getCoordinates(polygon.getInteriorRingN(j).getCoordinates()));
                }
                allPolygons.add(polyCoordinates);
            }
            geoJsonGeometry.put("coordinates", allPolygons);
        } else if (geometry instanceof Point) {
            Point point = (Point) geometry;
            geoJsonGeometry.put("coordinates", new double[]{point.getX(), point.getY()});
        } else if (geometry instanceof LineString) {
            LineString line = (LineString) geometry;
            geoJsonGeometry.put("coordinates", getCoordinates(line.getCoordinates()));
        } else {
            System.err.println("Type de géométrie non supporté : " + type);
            return null;
        }
        return geoJsonGeometry;
    }

    private static List<double[]> getCoordinates(Coordinate[] coords) {
        List<double[]> points = new ArrayList<>();
        for (Coordinate coord : coords) {
            points.add(new double[]{coord.x, coord.y});
        }
        return points;
    }
}