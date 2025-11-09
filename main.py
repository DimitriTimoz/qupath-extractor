#!/usr/bin/env python3
import os
import json
import javaobj.v2 as javaobj
import pickle

def parse_qpdata(file_path: str):
    """
    Parse a QuPath .qpdata file.
    QuPath files contain: version header + JSON metadata + Java serialized hierarchy
    Returns a dictionary with metadata and hierarchy information.
    """
    if not os.path.exists(file_path):
        raise FileNotFoundError(f"File not found: {file_path}")

    result = {
        'version': None,
        'metadata': None,
        'hierarchy': None,
        'raw_strings': []
    }

    with open(file_path, "rb") as f:
        # Read the entire file
        data = f.read()
        
        # 1. Extract version header
        if data.startswith(b'Data file version'):
            version_end = data.find(b'\x74', 19)  # Find end of version string
            if version_end > 0:
                result['version'] = data[19:version_end].decode('utf-8', errors='ignore')
        
        # 2. Look for JSON data (starts with { and contains common keys)
        json_pattern = b'{"dataVersion"'
        json_start = data.find(json_pattern)
        
        if json_start > 0:
            # Find the end of JSON by looking for closing brace followed by Java serialization marker
            # Java serialization often starts with 'sr' (serialized object marker)
            json_end = data.find(b'}sr ', json_start)
            if json_end > 0:
                json_end += 1  # Include the closing brace
                json_str = data[json_start:json_end].decode('utf-8', errors='ignore')
                try:
                    result['metadata'] = json.loads(json_str)
                except json.JSONDecodeError as e:
                    result['metadata_raw'] = json_str[:500]
        
        # 3. Extract readable strings that might contain class names or properties
        import re
        text_matches = re.findall(b'[\x20-\x7e]{10,}', data)
        result['raw_strings'] = [t.decode('ascii', errors='ignore') for t in text_matches[:30]]
        
        # 4. Try to parse Java objects after the JSON
        if json_end and json_end > 0:
            java_start = json_end + 2  # Skip '}sr'
            f.seek(java_start)
            try:
                pobj = javaobj.load(f)
                result['hierarchy'] = pobj
            except Exception as e:
                pass  # Silent fail for Java hierarchy parsing
    
    return result

def extract_annotations(qpdata_obj):
    """
    Extract annotation objects from a parsed QuPath data object.
    Returns a list of annotation information.
    """
    annotations = []
    
    if qpdata_obj is None:
        return annotations
    
    # QuPath stores data in a PathObjectHierarchy
    # Navigate the object structure
    try:
        # Try to access the hierarchy
        if hasattr(qpdata_obj, 'annotations'):
            # Directly accessible annotations
            for ann in qpdata_obj.annotations:
                annotations.append(extract_annotation_info(ann))
        elif hasattr(qpdata_obj, '__dict__'):
            # Search through object attributes
            for key, value in qpdata_obj.__dict__.items():
                print(f"Found attribute: {key} -> {type(value)}")
                if 'pathobject' in key.lower() or 'annotation' in key.lower():
                    if isinstance(value, list):
                        for item in value:
                            annotations.append(extract_annotation_info(item))
                    else:
                        annotations.append(extract_annotation_info(value))
        
        # Try to find map or list structures
        if hasattr(qpdata_obj, 'map'):
            for key, value in qpdata_obj.map.items():
                if 'annotation' in str(key).lower():
                    annotations.append(extract_annotation_info(value))
                    
    except Exception as e:
        print(f"Error extracting annotations: {e}")
    
    return annotations


def extract_annotation_info(ann_obj):
    """
    Extract relevant information from a single annotation object.
    """
    info = {
        'type': str(type(ann_obj)),
        'attributes': {}
    }
    
    if hasattr(ann_obj, '__dict__'):
        for key, value in ann_obj.__dict__.items():
            # Store key annotation properties
            if any(keyword in key.lower() for keyword in ['name', 'class', 'type', 'roi', 'geometry', 'measurement']):
                info['attributes'][key] = str(value)[:200]  # Limit string length
    
    return info

    
def get_qupath_info(path, verbose=False):
    f = os.path.join(path, "project.qpproj")
    data_path = os.path.join(path, "data")
    classifiers_path = os.path.join(path, "classifiers", "classes.json")
    
    # Load classification info
    classes = {}
    if os.path.exists(classifiers_path):
        with open(classifiers_path, "r") as cf:
            class_data = json.load(cf)
            for cls in class_data.get("pathClasses", []):
                classes[cls["name"]] = cls["color"]
    
    image_infos = []
    with open(f, "r") as file:
        data = json.load(file)
        images = data.get("images", [])
        total_images = len(images)
        print(f"Found {total_images} images in project")
        
        for idx, image in enumerate(images, 1):
            print(f"Processing image {idx}/{total_images}...", end="\r")
            image_info = {}
            if "serverBuilder" in image:
                server_builder = image["serverBuilder"]
                image_info["uri"] = server_builder.get("uri", "Unknown")
            if "entryID" in image:
                image_info["entryID"] = image["entryID"]
                image_data_path = os.path.join(data_path, str(image_info["entryID"]))
                if os.path.exists(image_data_path):
                    # Read summary.json for annotation counts
                    summary_path = os.path.join(image_data_path, "summary.json")
                    if os.path.exists(summary_path):
                        with open(summary_path, "r") as sf:
                            summary = json.load(sf)
                            image_info["summary"] = summary
                            if verbose:
                                print(f"\n=== Image {idx} Summary ===")
                                print(f"Image Type: {summary.get('imageType')}")
                                if 'hierarchy' in summary:
                                    hier = summary['hierarchy']
                                    print(f"Total Objects: {hier.get('nObjects')}")
                                    print(f"Object Type Counts: {hier.get('objectTypeCounts')}")
                                    print(f"Annotation Classifications: {hier.get('annotationClassificationCounts')}")
                                    print(f"Detection Classifications: {hier.get('detectionClassificationCounts')}")
                    
                    # Parse the data.qpdata file
                    image_data_path_qpdata = os.path.join(image_data_path, "data.qpdata")
                    image_info["data_path"] = image_data_path_qpdata
                    
                    # Read the file
                    try:
                        parsed_data = parse_qpdata(image_data_path_qpdata)
                        
                        # Display metadata (only for first image or if verbose)
                        if (idx == 1 or verbose) and parsed_data.get('metadata'):
                            metadata = parsed_data['metadata']
                            if verbose:
                                print(f"\n=== QuPath Metadata ===")
                                print(f"QuPath Version: {metadata.get('qupathVersion')}")
                                print(f"Data Version: {metadata.get('dataVersion')}")
                            
                            if 'server' in metadata:
                                server = metadata['server']
                                if 'metadata' in server:
                                    img_meta = server['metadata']
                                    if verbose:
                                        print(f"\nImage Information:")
                                        print(f"  Name: {img_meta.get('name')}")
                                        print(f"  Dimensions: {img_meta.get('width')}x{img_meta.get('height')}")
                                        print(f"  Magnification: {img_meta.get('magnification')}x")
                                        print(f"  Pixel Type: {img_meta.get('pixelType')}")
                                        print(f"  Channel Type: {img_meta.get('channelType')}")
                                    
                            image_info['metadata'] = metadata
                        
                        # Store parsed data info
                        image_info['parsed_data'] = {
                            'has_metadata': parsed_data.get('metadata') is not None,
                            'has_hierarchy': parsed_data.get('hierarchy') is not None,
                            'version': parsed_data.get('version')
                        }
                        image_info['classes'] = classes
                        
                    except Exception as e:
                        if verbose:
                            print(f"Error parsing {image_data_path_qpdata}: {e}")
                            import traceback
                            traceback.print_exc()
                        image_info["error"] = str(e)
                        
            image_infos.append(image_info)
        
        print()  # New line after progress
    
    return image_infos

if __name__ == "__main__":
    # Get passed arguments
    import sys
    args = sys.argv[1:]
    
    current_directory = os.path.dirname(os.path.abspath(__file__))
    path = args[0]
    
    # Extract info from qpdata files
    print("Analyzing QuPath project...")
    info = get_qupath_info(path, verbose=False)
    
    # Export to JSON
    output_file = os.path.join(current_directory, "annotations_export.json")
    with open(output_file, "w") as f:
        json.dump(info, f, indent=2)
    
    # Print summary
    print("\n" + "="*60)
    print("QuPath project analysis complete")
    print(f"✓ Total images processed: {len(info)}")
    
    # Count total annotations
    total_annotations = 0
    for img in info:
        if 'summary' in img and 'hierarchy' in img['summary']:
            ann_counts = img['summary']['hierarchy'].get('annotationClassificationCounts', {})
            total_annotations += sum(ann_counts.values())
    
    print(f"✓ Total annotations: {total_annotations}")
    print(f"✓ Data exported to: {output_file}")
    print("="*60)