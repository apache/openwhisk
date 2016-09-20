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

func main(params:[String:Any]) -> [String:Any] {
    let NumDays = 2
    var filteredForecasts = [[String:Any]]()
    #if os(Linux)
        if let forecasts = params["forecasts"] as? [Any] {
            for day in 0...(NumDays - 1) {
                if let forecast = forecasts[day] as? [String:Any] {
                    var terse = [String:Any]()
                    terse["dow"]       = forecast["dow"]
                    terse["narrative"] = forecast["narrative"]
                    terse["min_temp"]  = forecast["min_temp"]
                    terse["max_temp"]  = forecast["max_temp"]
                    filteredForecasts.append(terse)
                }
            }
        }
    #else
        if let forecasts = params["forecasts"] as? [[String:AnyObject]] {
            for day in 0...(NumDays - 1) {
                let forecast = forecasts[day] as [String:AnyObject]
                var terse = [String:Any]()
                terse["dow"]       = forecast["dow"]
                terse["narrative"] = forecast["narrative"]
                terse["min_temp"]  = forecast["min_temp"]
                terse["max_temp"]  = forecast["max_temp"]
                filteredForecasts.append(terse)
                
            }
        }
        
    #endif
    return [ "forecasts": filteredForecasts ]
}

