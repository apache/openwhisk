package hello;

import com.google.gson.JsonObject;

public class HelloJava {
    public static JsonObject main(JsonObject args) {
        String name;

        try {
            name = args.getAsJsonPrimitive("name").getAsString();
        } catch(Exception e) {
            name = "stranger";
        }

        JsonObject response = new JsonObject();
        response.addProperty("greeting", "Hello " + name + "!");
        return response;
    }
}
