package tests

case class ExpectTestCase(
    input: InputFile,
    obtained: () => String
)
