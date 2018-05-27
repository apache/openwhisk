function main() {
  return new Promise(function (resolve, reject) {
    setTimeout(function () {
      resolve({done: true});
    }, 175);
  })
}
