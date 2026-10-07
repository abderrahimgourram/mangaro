import nodemailer from 'nodemailer';
import { createSupportHandlers, SUPPORT_EMAIL } from '../lib/support-service.js';

const handlers = createSupportHandlers({
  settings: () => ({
    user: process.env.SUPPORT_SMTP_USER || SUPPORT_EMAIL,
    password: process.env.SUPPORT_SMTP_PASSWORD,
    secret: process.env.SUPPORT_FORM_SECRET,
    deploymentOrigin: process.env.VERCEL_URL ? `https://${process.env.VERCEL_URL}` : null,
    local: !process.env.VERCEL,
  }),
  sendMail: async (message, password) => {
    const transport = nodemailer.createTransport({
      host: 'smtp.gmail.com',
      port: 465,
      secure: true,
      auth: { user: process.env.SUPPORT_SMTP_USER || SUPPORT_EMAIL, pass: password.replace(/\s/g, '') },
      connectionTimeout: 5000,
      greetingTimeout: 5000,
      dnsTimeout: 3000,
      socketTimeout: 10000,
      logger: false,
      debug: false,
    });
    try { return await transport.sendMail(message); }
    finally { transport.close(); }
  },
});

export async function GET() { return handlers.get(); }
export async function POST(request) { return handlers.post(request); }
