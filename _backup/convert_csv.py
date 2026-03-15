import csv
import json

def convert():
    csv_file = r"t:\Program Files\Antigravity\Projects\Wine-Manager-Android\_backup\WineTracker_Backup_Master.csv"
    json_file = r"t:\Program Files\Antigravity\Projects\Wine-Manager-Android\_backup\WineTracker_Backup_Master.json"
    
    data = {
        "definitions": [],
        "bottles": [],
        "notes": []
    }
    
    current_section = None
    headers = []
    
    with open(csv_file, 'r', encoding='utf-8') as f:
        reader = csv.reader(f)
        for row in reader:
            if not row: continue
            
            first_cell = row[0].strip()
            if first_cell.startswith('---'):
                if 'Definitions' in first_cell:
                    current_section = 'definitions'
                elif 'Bottles' in first_cell:
                    current_section = 'bottles'
                elif 'Tasting Notes' in first_cell:
                    current_section = 'notes'
                headers = []
                continue
                
            if current_section and not headers:
                # First row after section is header
                headers = [col.strip() for col in row if col.strip()]
                continue
                
            if not first_cell or not first_cell.isdigit():
                continue # Skip empties or badly formed rows
                
            if current_section == 'definitions':
                data["definitions"].append({
                    "id": int(row[0]) if len(row) > 0 and row[0] else None,
                    "maker": row[1] if len(row) > 1 else None,
                    "name": row[2] if len(row) > 2 else None,
                    "type": row[3] if len(row) > 3 else None,
                    "country": row[4] if len(row) > 4 else None,
                    "region": row[5] if len(row) > 5 and row[5] != "null" else None,
                    "subregion": row[6] if len(row) > 6 and row[6] != "null" else None
                })
            elif current_section == 'bottles':
                data["bottles"].append({
                    "id": int(row[0]) if len(row) > 0 and row[0] else None,
                    "definitionId": int(row[1]) if len(row) > 1 and row[1] else None,
                    "vintage": row[2] if len(row) > 2 and row[2] != "null" else None,
                    "quantity": int(row[3]) if len(row) > 3 and row[3] else 0,
                    "price": float(row[4]) if len(row) > 4 and row[4] else 0.0,
                    "location": row[5] if len(row) > 5 and row[5] != "null" else None,
                    "status": row[6] if len(row) > 6 and row[6] != "null" else None,
                    "dateAdded": row[7] if len(row) > 7 and row[7] != "null" else None,
                    "dateConsumed": row[8] if len(row) > 8 and row[8] != "null" else None
                })
            elif current_section == 'notes':
                # Old format
                data["notes"].append({
                    "id": int(row[0]) if len(row) > 0 and row[0] else None,
                    "bottleId": int(row[1]) if len(row) > 1 and row[1] else None,
                    "definitionId": int(row[2]) if len(row) > 2 and row[2] else None,
                    "dateTasted": row[3] if len(row) > 3 and row[3] != "null" else None, 
                    "rating": float(row[4]) if len(row) > 4 and row[4] else 0.0,
                    "notes": row[5] if len(row) > 5 and row[5] != "null" else None,
                    "rebuy": str(row[6]).strip().lower() == 'true' if len(row) > 6 else False
                })
                
    with open(json_file, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=2)

if __name__ == "__main__":
    convert()
