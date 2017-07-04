package bridge

import "net/url"

type fakeFactory struct{}

func (f *fakeFactory) New(uri *url.URL) RegistryAdapter {

	return &fakeAdapter{}
}

type fakeAdapter struct{}

func (f *fakeAdapter) Ping() error {
	return nil
}
func (f *fakeAdapter) Register(service *Service) error {
	return nil
}
func (f *fakeAdapter) Deregister(service *Service) error {
	return nil
}
func (f *fakeAdapter) Refresh(service *Service) error {
	return nil
}
