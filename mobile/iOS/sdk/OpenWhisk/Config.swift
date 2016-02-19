/*
* Copyright 2015-2016 IBM Corporation
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

import Foundation

/*
 Retrieves basic configuration information for the SDK specified in WhiskConfig.plist or environment variables
*/
public class Config {
    
    public class func getHostAndPath(type type:String) -> String? {
        
        var url: String? = nil
        if let dict = getConfigDictionary() {
            url = dict.valueForKey(type) as? String
        } else {
            print("Configuration file missing, cannot config network call")
        }
        
        return url
    }
    
    
    private class func getConfigDictionary() -> NSDictionary? {
        
        // Attempt 1, load the bundle from a local reference to this classes bundle
        // I'am assuming the WhiskResources bundle is in the framework's root bundle
        let frameworkBundle = NSBundle(forClass: Config.self)
        
        if let bundlePath = frameworkBundle.pathForResource("OpenWhiskResources", ofType: "bundle") {
            if let bundle = NSBundle(path: bundlePath) {
                let configFile = bundle.pathForResource("OpenWhiskConfig", ofType: "plist")
                
                if let configFile = configFile {
                    let config = NSDictionary(contentsOfFile: configFile) as? [String: AnyObject]
                    if let config = config {
                        let urlConfig = config["Locations"] as? [String: String]
                        return urlConfig
                    }
                }
            }
        } else if let bundlePath = frameworkBundle.pathForResource("OpenWhiskWatchResources", ofType: "bundle") {
            if let bundle = NSBundle(path: bundlePath) {
                let configFile = bundle.pathForResource("OpenWhiskConfig", ofType: "plist")
                
                if let configFile = configFile {
                    let config = NSDictionary(contentsOfFile: configFile) as? [String: AnyObject]
                    if let config = config {
                        let urlConfig = config["Locations"] as? [String: String]
                        return urlConfig
                    }
                }
            }
        } else {
            if let configFile = frameworkBundle.pathForResource("OpenWhiskConfig", ofType: "plist") {
                let config = NSDictionary(contentsOfFile: configFile) as? [String: AnyObject]
                if let config = config {
                    let urlConfig = config["Locations"] as? [String: String]
                    return urlConfig
                }
            } else {
                print("Can't find configuration information")
            }
        }
        
        return nil
        
    }
    
    
    
    /*
     Can be used to read authentication credentials from env variables.  Useful for unit tests and maybe some build tasks
     but not much else?
    */
    public class func getAuthToken() -> (apiKey: String?, apiSecret: String?)? {
        
        let dict = NSProcessInfo.processInfo().environment
        let key = dict["TESTAPIKEY"]
        let secret = dict["TESTAPISECRET"]
        
        return(key, secret)
    }
    
}