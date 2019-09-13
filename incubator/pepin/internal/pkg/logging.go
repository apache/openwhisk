package pkg

import (
	"fmt"
	"os"
	"sync"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

var once sync.Once
var core zapcore.Core

func GetLogger(production bool) (logger *zap.SugaredLogger, err error) {
	once.Do(func() {
		atom := zap.NewAtomicLevel()
		if production {
			fmt.Println("Starting with production logging.")
			encoderCfg := zap.NewProductionEncoderConfig()
			atom.SetLevel(zapcore.WarnLevel)
			core = zapcore.NewCore(
				zapcore.NewJSONEncoder(encoderCfg),
				zapcore.Lock(os.Stdout),
				atom,
			)
		} else {
			fmt.Println("Starting with DEV logging.")
			encoderCfg := zap.NewDevelopmentEncoderConfig()
			atom.SetLevel(zapcore.DebugLevel)
			core = zapcore.NewCore(
				zapcore.NewJSONEncoder(encoderCfg),
				zapcore.Lock(os.Stdout),
				atom,
			)
		}
	})
	return zap.New(core).Sugar(), nil
}
