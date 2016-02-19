#/bin/bash
SCHEMA='{
  "type" : "object",
  "properties" : {
    "price" : {"type" : "number"},
    "name" : {"type" : "string"}
  }
}'
SCHEMA=`cat schema.json`
GOOD='{"name" : "Eggs", "price" : 34.99}'
BAD='{"name" : "Eggs", "price" : "Invalid"}'
GOOD='{"name" : "Eggs", "publish" : true}'
echo $SCHEMA 
echo $GOOD
echo $BAD
./validate.py "$GOOD" "$SCHEMA"
./validate.py "$BAD" "$SCHEMA"
