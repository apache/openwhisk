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

package wski18n

import (
    "path/filepath"
    "strings"

    goi18n "github.com/nicksnyder/go-i18n/i18n"
)

const (
    DEFAULT_LOCALE = "en_US"
)

var SUPPORTED_LOCALES = []string{
    "de_DE",
    "en_US",
    "es_ES",
    "fr_FR",
    "it_IT",
    "ja_JA",
    "ko_KR",
    "pt_BR",
    "zh_Hans",
    "zh_Hant",
}

var resourcePath = filepath.Join("wski18n", "resources")

func GetResourcePath() string {
    return resourcePath
}

func SetResourcePath(path string) {
    resourcePath = path
}

var T goi18n.TranslateFunc
var curLocale string

func init() {
    curLocale = Init(new(JibberJabberDetector))
}

func CurLocale() string {
    return curLocale
}

func Locale(detector Detector) string {

    // Use default locale until strings are translated
    /*sysLocale := normalize(detector.DetectLocale())
    if isSupported(sysLocale) {
        return sysLocale
    }

    locale := defaultLocaleForLang(detector.DetectLanguage())
    if locale != "" {
        return locale
    }*/

    return DEFAULT_LOCALE
}

func Init(detector Detector) string {
    l := Locale(detector)
    InitWithLocale(l)
    return l
}

func InitWithLocale(locale string) {
    err := loadFromAsset(locale)
    if err != nil {
        panic(err)
    }
    T = goi18n.MustTfunc(locale)
}

func loadFromAsset(locale string) (err error) {
    assetName := locale + ".all.json"
    assetKey := filepath.Join(resourcePath, assetName)
    bytes, err := Asset(assetKey)
    if err != nil {
        return
    }
    err = goi18n.ParseTranslationFileBytes(assetName, bytes)
    return
}

func normalize(locale string) string {
    locale = strings.ToLower(strings.Replace(locale, "-", "_", 1))
    for _, l := range SUPPORTED_LOCALES {
        if strings.EqualFold(locale, l) {
            return l
        }
    }
    switch locale {
    case "zh_cn", "zh_sg":
        return "zh_Hans"
    case "zh_hk", "zh_tw":
        return "zh_Hant"
    }
    return locale
}

func isSupported(locale string) bool {
    for _, l := range SUPPORTED_LOCALES {
        if strings.EqualFold(locale, l) {
            return true
        }
    }
    return false
}

func defaultLocaleForLang(lang string) string {
    if lang != "" {
        lang = strings.ToLower(lang)
        for _, l := range SUPPORTED_LOCALES {
            if lang == LangOfLocale(l) {
                return l
            }
        }
    }
    return ""
}

func LangOfLocale(locale string) string {
    if len(locale) < 2 {
        return ""
    }
    return locale[0:2]
}
