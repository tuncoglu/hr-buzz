# HR Buzz

A heart-rate alarm and run tracker for Bluetooth chest straps (Polar H10 and others), with an
optional AI coach. It records heart rate, GPS routes and fitness tests, so it is open source: you
can read exactly what it does with that data.

- **Source and build instructions:** [hr-buzz/](hr-buzz/) (start with [hr-buzz/README.md](hr-buzz/README.md))
- **How your data is handled, with a pointer to the code for every claim:**
  [hr-buzz/DATA-HANDLING.md](hr-buzz/DATA-HANDLING.md)
- **Privacy policy:** https://hrbuzz.sourdoughcalculator.uk/privacy/
- **Reporting a security or privacy problem:** [SECURITY.md](SECURITY.md)

In one paragraph: no account, no ads, no analytics. Everything stays on the phone unless you turn
on the coach and choose an online AI service with your own key; then a summary of your training
(never GPS or second-by-second heart rate) goes to that service. The coach can also run on the
phone with Google's Gemma model, sending nothing anywhere. One file, `Llm.java`, is the only code
that opens a network connection.

The Play Store build is made by `hr-buzz/ondevice/build.sh release`; the small build with no
third-party libraries is `hr-buzz/build.sh`. Both are shell/Gradle scripts you can run yourself
(`hr-buzz/README.md`, "Build"). `polar-h10-heart-rate-alert-research.md` is the research note that
started the project.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Copyright 2026 ENFTC. HR Buzz is
independent of Polar Electro Oy and of the AI services it can talk to.
