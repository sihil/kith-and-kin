import {map} from './utils.js'

function Button(props) {
    const classes = "button is-large "+props.colour;
    return (
        <button className={classes} value={props.value} onClick={() => props.onClick()}>{props.text}</button>
    )
}

class Textbox extends React.Component {
    constructor(props) {
        super(props);
        this.state = {
            value: props.text
        };
        this.timer = null;

        this.handleChange = this.handleChange.bind(this);
    }

    update(text) {
        this.props.onClick(text)
    }

    handleChange(event) {
        const text = event.target.value;
        this.setState({value: text});
        if (this.timer) window.clearTimeout(this.timer);
        this.timer = window.setTimeout(() => this.update(text), 2000)
    }

    render() {
        return (
            <div>
                <textarea className="textarea" value={this.state.value} onChange={this.handleChange} />
            </div>
        );
    }
}

class Question extends React.Component {


    renderAnswer(questionType) {
        switch (questionType) {
            case "multipleChoice":
                const answers = this.props.question.answers;
                return answers.map((answer, index) => {
                    const colour = (this.props.answer === answer.updateKey) ? "is-success" : "";
                    return (
                        <Button key={index} colour={colour} text={answer.text}
                                onClick={() => this.props.onAnswer(answer.updateKey)}/>
                    );
                });
            case "text":
                return (
                    <Textbox text={this.props.answer} onClick={(newAnswer) => this.props.onAnswer(newAnswer)}/>
                );
        }
    }

    render() {
        const question = this.props.question;
        const htmlQuestion = <h2 className="heading">{question.question}</h2>;

        const htmlAnswer = this.renderAnswer(question.questionType);
        return (
            <div className="question">
                {htmlQuestion}
                {question.helpText &&
                    <p>{question.helpText}</p>
                }
                {htmlAnswer}
            </div>
        )
    }
}

class Rsvp extends React.Component {
    constructor() {
        super();
        this.state = {
            questions: null,
            startKey: null,
            answers: {},
            unsent: true
        }
    }
    findAnswer(question, answerKey) {
        return question.answers.find((answer) => { return answer.updateKey === answerKey });
    }
    componentDidMount() {
        // get questions
        fetch("/rsvp/questions", {
            credentials: 'include'
        }).then((response) => {
            return response.json().then((json) => {
                const answerMap = json.answers;
                this.setState({questions: json.questions, startKey: json.startPage, answers: answerMap, unsent: json.unsent});
            });
        });
        // get any responses
    }
    update(answers, complete) {
        return fetch("/rsvp/update?complete="+complete, {
            credentials: 'include',
            headers: {
                'Content-Type': 'application/json',
                'Csrf-Token': window.csrf
            },
            method: 'POST',
            body: JSON.stringify(answers)
        })
    }
    onAnswer(question, answer) {
        const answers = {};
        answers[question.updateKey] = answer;
        this.update(answers, false).then((res) => {
            if (res.status == 200) {
                this.setState((previous) => {
                    const newAnswer = {};
                    newAnswer[question.updateKey] = answer;
                    return {answers: Object.assign({}, previous.answers, newAnswer), unsent: true};
                });
            }
        });
    }
    nextQuestion(question, answerObject) {
        switch(question.questionType) {
            case "multipleChoice":
                return answerObject.nextQuestion;
            case "text":
                return question.nextQuestion;
        }
    }
    questionAnswers(key) {
        const question = this.state.questions[key];
        if (question) {
            const answerKey = this.state.answers[question.updateKey];
            if (answerKey) {
                const answerObject = this.findAnswer(question, answerKey);
                const nextQuestion = this.nextQuestion(question, answerObject);
                return [{question: question, answer: answerKey}].concat(this.questionAnswers(nextQuestion));
            } else {
                return [{question: question}];
            }
        } else return [];
    }
    renderQuestion(question, answer) {
        return (
            <Question key={question.updateKey} question={question} answer={answer} onAnswer={(answer) => this.onAnswer(question, answer)}/>
        );
    }
    finishRsvp() {
        this.update(this.state.answers, true).then((res) => {
            if (res.status == 200) {
                window.location = '/rsvp/complete'
            }
        })
    }
    render() {
        if (this.state.questions) {
            const questionList = this.questionAnswers(this.state.startKey);
            const questionElements = questionList.map((question) => {
                return this.renderQuestion(question.question, question.answer);
            });
            const bottomQuestion = questionList[questionList.length-1];
            const terminusQuestion = bottomQuestion.question.nextQuestion === undefined;
            const finished = terminusQuestion && bottomQuestion.answer;
            const unsent = this.state.unsent;
            return (
                <div>
                    {questionElements}
                    {finished &&
                        <div>
                            <h2 className="heading">You're finished!</h2>
                            {unsent && <Button colour="is-danger" onClick={() => this.finishRsvp()} text="Send RSVP"/>}
                        </div>
                    }
                </div>
            );
        } else {
            return (
                <p>Loading RSVP information...</p>
            );
        }
    }
}

ReactDOM.render(
    <Rsvp />,
    document.getElementById('container')
);