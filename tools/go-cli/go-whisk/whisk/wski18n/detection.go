package wski18n

import "github.com/cloudfoundry/jibber_jabber"

type Detector interface {
	DetectLocale() string
	DetectLanguage() string
}

type JibberJabberDetector struct{}

func (d *JibberJabberDetector) DetectLocale() string {
	userLocale, err := jibber_jabber.DetectIETF()
	if err != nil {
		userLocale = ""
	}
	return userLocale
}

func (d *JibberJabberDetector) DetectLanguage() string {
	lang, err := jibber_jabber.DetectLanguage()
	if err != nil {
		lang = ""
	}
	return lang
}