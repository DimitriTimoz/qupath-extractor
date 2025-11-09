import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.Project;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.images.ImageData;
import qupath.lib.objects.hierarchy.PathObjectHierarchy; 
import qupath.lib.io.GsonTools; 
import qupath.lib.images.servers.ImageServerBuilder; 
import qupath.lib.scripting.QP; 
import qupath.lib.images.servers.ImageServerProvider; 

import java.io.FileWriter; 
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.file.Paths;
import java.util.ServiceLoader; 
import java.util.Map;       
import java.util.List;      
import java.util.ArrayList; 
import java.util.HashMap;   
import java.io.File; 
import com.google.gson.Gson; 
import com.google.gson.GsonBuilder;
import org.locationtech.jts.geom.Geometry; 
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Coordinate;

public class ExtractAnnotations {

    public static void main(String[] args) throws Exception {
        
        // Initialisation de QuPath
        QP.getCoreClasses(); 
        ImageServerProvider.setServiceLoader(ServiceLoader.load(ImageServerBuilder.class));
        
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        
        // Définir le dossier et le nom du fichier de sortie unique
        String outputDirectory = "geojson_output";
        new File(outputDirectory).mkdirs();
        String outputFilename = Paths.get(outputDirectory, "toutes_les_annotations.geojson").toString();

        // 1. Créer la FeatureCollection une seule fois
        Map<String, Object> featureCollection = new HashMap<>();
        featureCollection.put("type", "FeatureCollection");
        List<Map<String, Object>> features = new ArrayList<>();
        featureCollection.put("features", features);

        Project<BufferedImage> project = ProjectIO.loadProject(
            Paths.get("/Volumes/Elements/QuPath 3 dataset entrainement en cours/project.qpproj").toFile(),
            BufferedImage.class
        );
        
        // Boucle sur chaque image du projet
        for (var entry : project.getImageList()) {
            System.out.println("Traitement de: " + entry.getImageName());
            
            ImageData<BufferedImage> imageData = entry.readImageData();
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            var annotations = hierarchy.getAnnotationObjects();

            if (annotations.isEmpty()) {
                continue; // Passe à l'image suivante
            }
            
            // Boucle sur les annotations de cette image
            for (var annotation : annotations) {
                
                // 2. Créer les "properties" (métadonnées)
                Map<String, Object> properties = new HashMap<>();
                properties.put("image_name", entry.getImageName()); // Ajoute le nom de l'image
                if (annotation.getPathClass() != null) {
                    properties.put("classification", annotation.getPathClass().toString());
                } else {
                    properties.put("classification", "(aucune)");
                }
                
                // 3. Créer la "geometry" (coordonnées)
                Geometry jtsGeometry = annotation.getROI().getGeometry();
                Map<String, Object> geometry = convertJtsToGeoJson(jtsGeometry);

                if (geometry != null) {
                    // 4. Assembler la "Feature" et l'ajouter à la liste globale
                    Map<String, Object> feature = new HashMap<>();
                    feature.put("type", "Feature");
                    feature.put("geometry", geometry);
                    feature.put("properties", properties);
                    features.add(feature);
                }
            } 
        } // Fin de la boucle des images

        // 5. Écrire le fichier GeoJSON unique à la fin
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

    /**
     * Convertit une géométrie JTS (utilisée par QuPath) en une Map
     * structurée pour GeoJSON.
     */
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

    /**
     * Helper pour convertir un tableau de Coordinate JTS en liste de [x, y]
     */
    private static List<double[]> getCoordinates(Coordinate[] coords) {
        List<double[]> points = new ArrayList<>();
        for (Coordinate coord : coords) {
            points.add(new double[]{coord.x, coord.y});
        }
        return points;
    }
}