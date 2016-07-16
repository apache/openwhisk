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


import UIKit
import OpenWhisk
import CoreLocation

class ViewController: UIViewController, CLLocationManagerDelegate {
    
    @IBOutlet weak var whiskButton: WhiskButton!
    @IBOutlet weak var outputText: UITextView!
    @IBOutlet weak var statusLabel: UILabel!
    
    // Change to your whisk app key and secret.
    let WhiskAppKey: String = "AppKey"
    let WhiskAppSecret: String = "AppSecret"
    
    // the URL for Whisk backend
    let baseUrl: String? = "https://openwhisk.ng.bluemix.net"
    
    // The action to invoke.
    
    // Choice: specify commponents
    let MyNamespace: String = "whisk.system"
    let MyPackage: String? = "util"
    let MyWhiskAction: String = "date"
    
    var MyActionParameters: [String:AnyObject]? = nil
    let HasResult: Bool = true // true if the action returns a result
    
    var session: NSURLSession!
    
    let locationManager = CLLocationManager()
    var currentLocation: [CLLocation]?
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        // create custom session that allows self-signed certificates
        let session = NSURLSession(configuration: NSURLSessionConfiguration.defaultSessionConfiguration(), delegate: SelfSignedNetworkDelegate(), delegateQueue:NSOperationQueue.mainQueue())
        
        // create whisk credentials token
        let creds = WhiskCredentials(accessKey: WhiskAppKey,accessToken: WhiskAppSecret)
        
        // Setup action using components
        whiskButton.setupWhiskAction(MyWhiskAction, package: MyPackage, namespace: MyNamespace, credentials: creds, hasResult: HasResult, parameters: MyActionParameters, urlSession: session, baseUrl: baseUrl)
        
        // setup location
        locationManager.delegate = self
        locationManager.requestWhenInUseAuthorization()
        
        navigationItem.title = "Whisk"
        
    }
    
    @IBAction func whiskButtonPressed(sender: AnyObject) {
        // Set latitude and longitude parameters.
        if let currentLocation = currentLocation {
            MyActionParameters = ["latitude": currentLocation[0].coordinate.latitude, "longitude": currentLocation[0].coordinate.longitude]
        }
        
        // Invoke action with parameters.
        whiskButton.invokeAction(parameters: MyActionParameters, actionCallback: { reply, error in
            if let error = error {
                print("Oh no! \(error)")
                if case let WhiskError.HTTPError(description, statusCode, _) = error {
                    print("HttpError: \(description) statusCode:\(statusCode)")
                }
            } else if let reply = reply {
                let str = "\(reply)"
                print("reply: \(str)")
                self.statusLabel.text = "Action \(self.MyNamespace)/\(self.MyWhiskAction) returned \(str.characters.count) characters"
                if let result = reply["result"] as? [String:AnyObject] {
                    self.displayOutput(result)
                }
            } else {
                print("Success")
            }
        })
    }
    
    
    func displayOutput(reply: [String:AnyObject]) {
        if let date = reply["date"] as? String{
            self.outputText.text = "The date is \(reformatDate(date))"
        }
    }
    
    // Optional, can be used to display results in a UITableView
    func displayResultView(reply: [String: AnyObject]) {
        let storyboard = UIStoryboard(name: "Main", bundle: nil)
        let vc = storyboard.instantiateViewControllerWithIdentifier("resultSetTable") as! ResultSetTableController
        vc.resultSet = reply
        self.navigationController?.pushViewController(vc, animated: true)
    }
    
    // CLLocationDelegate Functions
    func locationManager(manager: CLLocationManager, didChangeAuthorizationStatus status: CLAuthorizationStatus) {
        print("Got location manager authorization status \(status)")
        locationManager.startUpdatingLocation()
    }
    
    func locationManager(manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        currentLocation = locations
    }
    
    
    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        // Dispose of any resources that can be recreated.
    }
    
    // Convert string timestamp to a display format
    func reformatDate(dateStr: String) -> String {
        
        var newDateStr = dateStr
        let formatter = NSDateFormatter()
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
        
        formatter.timeZone = NSTimeZone(name: "UTC")
        
        if let date = formatter.dateFromString(dateStr) {
            formatter.dateFormat = "MMM dd EEEE yyyy HH:mm"
            formatter.timeZone = NSTimeZone(name: "UTC")
            newDateStr = formatter.stringFromDate(date)
        }
        
        return newDateStr
    }
    
    
}

