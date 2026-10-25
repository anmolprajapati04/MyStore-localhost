const tls = require('tls');
const net = require('net');

const user = 'anmolprajapati802@gmail.com';
const pass = 'uckbgresxhwltxya';
const to = user;

const socket = net.connect(587, 'smtp.gmail.com');
let stage = 'banner';
let buf = '';

function send(s, cmd) {
  console.log('>>', cmd.startsWith('AUTH') ? 'AUTH PLAIN ***' : cmd);
  s.write(cmd + '\r\n');
}

socket.setTimeout(25000, () => {
  console.log('TIMEOUT');
  process.exit(1);
});

socket.on('data', (d) => {
  buf += d.toString();
  process.stdout.write(d.toString());
  if (stage === 'banner' && buf.includes('220')) {
    buf = '';
    stage = 'ehlo1';
    send(socket, 'EHLO localhost');
  } else if (stage === 'ehlo1' && buf.includes('250 ')) {
    buf = '';
    stage = 'starttls';
    send(socket, 'STARTTLS');
  } else if (stage === 'starttls' && buf.includes('220')) {
    buf = '';
    stage = 'tls';
    const secure = tls.connect({ socket, servername: 'smtp.gmail.com' }, () => {
      let sbuf = '';
      send(secure, 'EHLO localhost');
      secure.on('data', (x) => {
        sbuf += x.toString();
        process.stdout.write(x.toString());
        if (!secure._auth && sbuf.includes('250 ')) {
          secure._auth = 1;
          sbuf = '';
          const token = Buffer.from(`\0${user}\0${pass}`).toString('base64');
          send(secure, 'AUTH PLAIN ' + token);
        } else if (secure._auth && !secure._mail && sbuf.startsWith('235')) {
          secure._mail = 1;
          sbuf = '';
          send(secure, `MAIL FROM:<${user}>`);
        } else if (secure._auth && sbuf.startsWith('535') || sbuf.includes('5.7.8') || sbuf.includes('5.7.0')) {
          console.log('AUTH_FAIL');
          process.exit(2);
        } else if (secure._mail && !secure._rcpt && sbuf.startsWith('250')) {
          secure._rcpt = 1;
          sbuf = '';
          send(secure, `RCPT TO:<${to}>`);
        } else if (secure._rcpt && !secure._data && sbuf.startsWith('250')) {
          secure._data = 1;
          sbuf = '';
          send(secure, 'DATA');
        } else if (secure._data && !secure._body && sbuf.startsWith('354')) {
          secure._body = 1;
          sbuf = '';
          secure.write(`From: MyStore <${user}>\r\nTo: ${to}\r\nSubject: MyStore SMTP probe\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nSMTP credentials work. Order confirmation mail can be sent.\r\n.\r\n`);
        } else if (secure._body && sbuf.startsWith('250')) {
          console.log('SMTP_SEND_OK');
          send(secure, 'QUIT');
          process.exit(0);
        }
      });
    });
    socket.removeAllListeners('data');
  }
});
