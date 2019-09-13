package datastore

import (
	"errors"
	"fmt"
)

type ActionMetaData struct {
	Name      string
	Namespace string
	Kind      string
}

type Datastore interface {
	InitStore() error
	GetAction(namespace string, name string) (ActionMetaData, error)
}

type MemoryDatastore struct {
	actions map[string]ActionMetaData
}

func (m *MemoryDatastore) InitStore() error {
	m.actions = make(map[string]ActionMetaData)
	//TODO: init from json
	m.actions["ns1/action1"] = ActionMetaData{"ns1", "action1", "nodejs-10"}
	m.actions["ns1/action2"] = ActionMetaData{"ns1", "action2", "nodejs-10"}
	m.actions["ns1/action3"] = ActionMetaData{"ns1", "action3", "nodejs-ow"}
	return nil
}
func (m *MemoryDatastore) GetAction(namespace string, name string) (ActionMetaData, error) {
	key := fmt.Sprintf("%s/%s", namespace, name)
	if val, ok := m.actions[key]; ok {
		return val, nil
	} else {
		return ActionMetaData{}, errors.New(fmt.Sprintf("Action %s not found", key))
	}
}
