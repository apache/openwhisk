function main() {
	var stop = new Date().getTime();
    while(new Date().getTime() < stop + 20000) {
        ;
    }
    return {payload: 'Helo Hamada'};
}
